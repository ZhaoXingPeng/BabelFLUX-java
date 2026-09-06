package com.babelflux.backend.websocket;

import com.babelflux.backend.service.SessionService;
import com.babelflux.backend.service.SessionTokenService;
import com.babelflux.backend.service.RealtimeSessionRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class SessionWebSocketHandler extends TextWebSocketHandler implements WebSocketHandler {
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final SessionTokenService tokens;
    private final RealtimeSessionRunner runner;
    private final SessionEventHub eventHub;
    private final ConcurrentMap<String, RealtimeSessionRunner.RunHandle> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionEventHub.Subscription> handoffSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Future<?>> handoffTasks = new ConcurrentHashMap<>();
    private final ExecutorService handoffExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                                   RealtimeSessionRunner runner) {
        this(mapper, sessions, tokens, runner, new SessionEventHub());
    }

    @Autowired
    public SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                                   RealtimeSessionRunner runner, SessionEventHub eventHub) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.tokens = tokens;
        this.runner = runner;
        this.eventHub = eventHub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        String id = pathVariable(socket, "sessionId");
        String token = query(socket, "token");
        if (token == null || token.isBlank()) {
            send(socket, Map.of("type", "error", "message", "Missing WebSocket token"));
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        boolean handoff = tokens.valid(id, token, "handoff");
        if (!handoff && !tokens.valid(id, token, "session")) {
            send(socket, Map.of("type", "error", "message", "Invalid WebSocket token"));
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.get(id);
        if (handoff) startHandoff(socket, id);
        else send(socket, Map.of("type", "session_started", "sessionId", id));
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        JsonNode payload;
        try {
            payload = mapper.readTree(message.getPayload());
        } catch (Exception error) {
            send(socket, Map.of("type", "error", "message", "Invalid JSON message"));
            return;
        }
        String type = payload.path("type").asText();
        String id = pathVariable(socket, "sessionId");
        if (handoffSubscriptions.containsKey(socket.getId())) {
            if ("stop_session".equals(type)) socket.close(CloseStatus.NORMAL);
            return;
        }
        var session = sessions.get(id);
        if ("start_session".equals(type)) {
            synchronized (runs) {
                if (runs.containsKey(socket.getId())) return;
                session.applyOverrides(text(payload, "sourceLanguage"), text(payload, "targetLanguage"),
                        text(payload, "domain"), text(payload, "inputMode"), text(payload, "sourceUrl"),
                        text(payload, "modelProfile"));
                session.start();
                AtomicBoolean reportEmitted = new AtomicBoolean();
                AtomicReference<RealtimeSessionRunner.RunHandle> handleRef = new AtomicReference<>();
                RealtimeSessionRunner.RunHandle handle = runner.start(session, event -> {
                    if ("session_report".equals(event.get("type"))) {
                        reportEmitted.set(true);
                        RealtimeSessionRunner.RunHandle current = handleRef.get();
                        if (current != null) runs.remove(socket.getId(), current);
                    }
                    eventHub.publish(id, event);
                    send(socket, event);
                });
                handleRef.set(handle);
                runs.put(socket.getId(), handle);
                if (reportEmitted.get()) runs.remove(socket.getId(), handle);
            }
        } else if ("stop_session".equals(type) || "audio_end".equals(type)) {
            RealtimeSessionRunner.RunHandle handle = runs.get(socket.getId());
            if (handle == null) {
                var report = sessions.finish(id);
                send(socket, Map.of("type", "session_report", "reportId", report.reportId(),
                        "correctionStatus", report.correctionStatus()));
            } else {
                handle.stop();
            }
        } else if ("media_clock".equals(type)) {
            Long playbackMs = clockValue(payload.get("playbackMs"));
            Long sentAudioMs = clockValue(payload.get("sentAudioMs"));
            if (playbackMs == null || sentAudioMs == null) {
                send(socket, Map.of("type", "error", "message",
                        "media_clock 的 playbackMs 和 sentAudioMs 必须是非负整数"));
                return;
            }
            RealtimeSessionRunner.RunHandle handle = runs.get(socket.getId());
            if (handle != null) handle.updateClientClock(playbackMs, sentAudioMs);
        } else if ("pause_session".equals(type)) {
            RealtimeSessionRunner.RunHandle handle = runs.get(socket.getId());
            if (handle != null) handle.pause();
            send(socket, Map.of("type", "source_sync_state", "state", Map.of("status", "missing", "lagMs", 0, "message", "会话已暂停")));
        } else if ("resume_session".equals(type)) {
            RealtimeSessionRunner.RunHandle handle = runs.get(socket.getId());
            if (handle != null) handle.resume();
            send(socket, Map.of("type", "source_sync_state", "state", Map.of("status", "listening", "lagMs", 0, "message", "会话已恢复")));
        } else {
            send(socket, Map.of("type", "error", "message", "Unsupported client event"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        if (handoffSubscriptions.containsKey(socket.getId())) return;
        RealtimeSessionRunner.RunHandle handle = runs.get(socket.getId());
        if (handle == null) {
            try {
                send(socket, Map.of("type", "error", "message", "Start the session before sending audio"));
            } catch (IOException error) {
                throw new IllegalStateException("failed to send audio state", error);
            }
            return;
        }
        ByteBuffer payload = message.getPayload().asReadOnlyBuffer();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        handle.acceptAudio(bytes);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        RealtimeSessionRunner.RunHandle handle = runs.remove(socket.getId());
        if (handle != null) handle.stop();
        SessionEventHub.Subscription subscription = handoffSubscriptions.remove(socket.getId());
        if (subscription != null) eventHub.unsubscribe(subscription);
        Future<?> task = handoffTasks.remove(socket.getId());
        if (task != null) task.cancel(true);
    }

    private void startHandoff(WebSocketSession socket, String sessionId) throws IOException {
        SessionEventHub.Subscription subscription = eventHub.subscribe(sessionId);
        handoffSubscriptions.put(socket.getId(), subscription);
        send(socket, Map.of("type", "session_started", "sessionId", sessionId));
        for (Map<String, Object> event : subscription.replay()) send(socket, event);
        Future<?> task = handoffExecutor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Map<String, Object> event = subscription.await(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (event != null) send(socket, event);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // The display-only socket may close while the primary session continues.
            }
        });
        handoffTasks.put(socket.getId(), task);
    }

    @PreDestroy
    void shutdown() {
        handoffTasks.values().forEach(task -> task.cancel(true));
        handoffExecutor.shutdownNow();
    }

    private void send(WebSocketSession socket, Object body) throws IOException {
        synchronized (socket) {
            socket.sendMessage(new TextMessage(mapper.writeValueAsString(body)));
        }
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
    private static String query(WebSocketSession socket, String key) { return socket.getUri() == null ? null : org.springframework.web.util.UriComponentsBuilder.fromUri(socket.getUri()).build().getQueryParams().getFirst(key); }
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
