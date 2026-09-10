package com.babelflux.backend.websocket;

import java.io.IOException;
import java.util.Map;
import org.springframework.web.socket.WebSocketSession;

/** Publishes session events for handoff subscribers before sending to the primary socket. */
final class SessionEventDispatcher {
    private final SessionEventHub eventHub;
    private final WebSocketEventSender sender;

    SessionEventDispatcher(SessionEventHub eventHub, WebSocketEventSender sender) {
        this.eventHub = eventHub;
        this.sender = sender;
    }

    void sendDirect(WebSocketSession socket, Object body) throws IOException { sender.send(socket, body); }

    void dispatchRunnerEvent(String sessionId, WebSocketSession socket, Map<String, Object> event) throws IOException {
        eventHub.publish(sessionId, event);
        if ("session_report".equals(event.get("type"))) eventHub.complete(sessionId);
        sender.send(socket, event);
    }

    void dispatchSourceState(String sessionId, WebSocketSession socket, String status, String message)
            throws IOException {
        Map<String, Object> event = Map.of("type", "source_sync_state",
                "state", Map.of("status", status, "lagMs", 0, "message", message));
        eventHub.publish(sessionId, event);
        sender.send(socket, event);
    }
}
