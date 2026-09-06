package com.babelflux.backend.websocket;

import com.babelflux.backend.service.SessionService;
import com.babelflux.backend.service.SessionTokenService;
import com.babelflux.backend.service.RealtimeSessionRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final ConcurrentMap<String, RealtimeSessionRunner.RunHandle> runs = new ConcurrentHashMap<>();

    public SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                                   RealtimeSessionRunner runner) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.tokens = tokens;
        this.runner = runner;
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
        if (!tokens.valid(id, token)) {
            send(socket, Map.of("type", "error", "message", "Invalid WebSocket token"));
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.get(id);
        send(socket, Map.of("type", "session_started", "sessionId", id));
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
        var session = sessions.get(id);
        if ("start_session".equals(type)) {
            synchronized (runs) {
                if (runs.containsKey(socket.getId())) return;
                session.start();
                AtomicBoolean reportEmitted = new AtomicBoolean();
                AtomicReference<RealtimeSessionRunner.RunHandle> handleRef = new AtomicReference<>();
                RealtimeSessionRunner.RunHandle handle = runner.start(session, event -> {
                    if ("session_report".equals(event.get("type"))) {
                        reportEmitted.set(true);
                        RealtimeSessionRunner.RunHandle current = handleRef.get();
                        if (current != null) runs.remove(socket.getId(), current);
                    }
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
    }

    private void send(WebSocketSession socket, Object body) throws IOException {
        synchronized (socket) {
            socket.sendMessage(new TextMessage(mapper.writeValueAsString(body)));
        }
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
