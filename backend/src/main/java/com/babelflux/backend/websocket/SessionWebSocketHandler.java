package com.babelflux.backend.websocket;

import com.babelflux.backend.infrastructure.RedisSessionRepository;
import com.babelflux.backend.service.RealtimeSessionRunner;
import com.babelflux.backend.service.SessionService;
import com.babelflux.backend.service.SessionTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** WebSocket protocol adapter. Session execution, leases and fan-out live in dedicated collaborators. */
@Component
public class SessionWebSocketHandler extends TextWebSocketHandler implements WebSocketHandler {
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final SessionTokenService tokens;
    private final RealtimeSessionCoordinator coordinator;
    private final SessionEventDispatcher dispatcher;
    private final HandoffSessionSubscriber handoffs;
    private final RunnerLeaseManager leases;

    public SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                                   RealtimeSessionRunner runner) {
        this(mapper, sessions, tokens, runner, new SessionEventHub(), (RedisSessionRepository) null);
    }

    public SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                                   RealtimeSessionRunner runner, SessionEventHub eventHub) {
        this(mapper, sessions, tokens, runner, eventHub, (RedisSessionRepository) null);
    }

    @Autowired
    public SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                                   RealtimeSessionRunner runner, SessionEventHub eventHub,
                                   ObjectProvider<RedisSessionRepository> redisProvider) {
        this(mapper, sessions, tokens, runner, eventHub, redisProvider.getIfAvailable());
    }

    SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                            RealtimeSessionRunner runner, SessionEventHub eventHub,
                            RedisSessionRepository redis) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.tokens = tokens;
        WebSocketEventSender sender = new WebSocketEventSender(mapper);
        this.dispatcher = new SessionEventDispatcher(eventHub, sender);
        this.handoffs = new HandoffSessionSubscriber(eventHub, sender);
        this.leases = new RunnerLeaseManager(redis);
        this.coordinator = new RealtimeSessionCoordinator(sessions, runner, leases);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        String sessionId = pathVariable(socket, "sessionId");
        String token = query(socket, "token");
        if (token == null || token.isBlank()) {
            reject(socket, "Missing WebSocket token");
            return;
        }
        boolean handoff = tokens.valid(sessionId, token, "handoff");
        if (!handoff && !tokens.valid(sessionId, token, "session")) {
            reject(socket, "Invalid WebSocket token");
            return;
        }
        sessions.get(sessionId);
        if (handoff) handoffs.start(socket, sessionId);
        else dispatcher.sendDirect(socket, Map.of("type", "session_started", "sessionId", sessionId));
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        JsonNode payload;
        try {
            payload = mapper.readTree(message.getPayload());
        } catch (Exception error) {
            dispatcher.sendDirect(socket, Map.of("type", "error", "message", "Invalid JSON message"));
            return;
        }
        String type = payload.path("type").asText();
        if (handoffs.isHandoff(socket.getId())) {
            if ("stop_session".equals(type)) socket.close(CloseStatus.NORMAL);
            return;
        }
        String sessionId = pathVariable(socket, "sessionId");
        switch (type) {
            case "start_session" -> start(socket, sessionId, payload);
            case "stop_session", "audio_end" -> stop(socket, sessionId);
            case "media_clock" -> updateClock(socket, payload);
            case "pause_session" -> {
                coordinator.pause(socket.getId());
                dispatcher.dispatchSourceState(sessionId, socket, "missing", "会话已暂停");
            }
            case "resume_session" -> {
                coordinator.resume(socket.getId());
                dispatcher.dispatchSourceState(sessionId, socket, "listening", "会话已恢复");
            }
            default -> dispatcher.sendDirect(socket, Map.of("type", "error", "message", "Unsupported client event"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        if (handoffs.isHandoff(socket.getId())) return;
        if (!coordinator.hasRun(socket.getId())) {
            try {
                dispatcher.sendDirect(socket, Map.of("type", "error", "message", "Start the session before sending audio"));
            } catch (IOException error) {
                throw new IllegalStateException("failed to send audio state", error);
            }
            return;
        }
        ByteBuffer payload = message.getPayload().asReadOnlyBuffer();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        coordinator.acceptAudio(socket.getId(), bytes);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        if (handoffs.close(socket.getId())) return;
        coordinator.close(pathVariable(socket, "sessionId"), socket.getId());
    }

    @PreDestroy
    void shutdown() {
        handoffs.shutdown();
        coordinator.shutdown();
        leases.shutdown();
    }

    private void start(WebSocketSession socket, String sessionId, JsonNode payload) throws Exception {
        RealtimeSessionCoordinator.CommandResult result = coordinator.start(sessionId, socket.getId(),
                new RealtimeSessionCoordinator.StartRequest(text(payload, "sourceLanguage"),
                        text(payload, "targetLanguage"), text(payload, "domain"), text(payload, "inputMode"),
                        text(payload, "sourceUrl"), text(payload, "modelProfile")),
                event -> dispatcher.dispatchRunnerEvent(sessionId, socket, event));
        if (result.failed()) dispatcher.sendDirect(socket, Map.of("type", "error", "message", result.error()));
    }

    private void stop(WebSocketSession socket, String sessionId) throws IOException {
        RealtimeSessionCoordinator.CommandResult result = coordinator.stop(sessionId, socket.getId());
        if (result.failed()) {
            dispatcher.sendDirect(socket, Map.of("type", "error", "message", result.error()));
            return;
        }
        if (result.report() != null) {
            dispatcher.sendDirect(socket, Map.of("type", "session_report", "reportId", result.report().reportId(),
                    "correctionStatus", result.report().correctionStatus()));
        }
    }

    private void updateClock(WebSocketSession socket, JsonNode payload) throws IOException {
        Long playbackMs = clockValue(payload.get("playbackMs"));
        Long sentAudioMs = clockValue(payload.get("sentAudioMs"));
        if (playbackMs == null || sentAudioMs == null) {
            dispatcher.sendDirect(socket, Map.of("type", "error", "message",
                    "media_clock 的 playbackMs 和 sentAudioMs 必须是非负整数"));
            return;
        }
        coordinator.updateClock(socket.getId(), playbackMs, sentAudioMs);
    }

    private void reject(WebSocketSession socket, String message) throws IOException {
        dispatcher.sendDirect(socket, Map.of("type", "error", "message", message));
        socket.close(CloseStatus.POLICY_VIOLATION);
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static Long clockValue(JsonNode value) {
        if (value == null || value.isNull()) return 0L;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) return null;
        return value.longValue();
    }

    private static String query(WebSocketSession socket, String key) {
        return socket.getUri() == null ? null
                : org.springframework.web.util.UriComponentsBuilder.fromUri(socket.getUri()).build()
                        .getQueryParams().getFirst(key);
    }

    private static String pathVariable(WebSocketSession socket, String key) {
        Object attribute = socket.getAttributes().get(key);
        if (attribute != null) return attribute.toString();
        if (socket.getUri() == null) throw new IllegalArgumentException("missing WebSocket path variable: " + key);
        String path = socket.getUri().getPath();
        int marker = path.lastIndexOf("/sessions/");
        if (marker < 0) throw new IllegalArgumentException("invalid WebSocket path");
        String value = path.substring(marker + "/sessions/".length());
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }
}
