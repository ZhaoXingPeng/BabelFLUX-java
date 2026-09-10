package com.babelflux.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Serializes outbound events and keeps each socket write ordered. */
final class WebSocketEventSender {
    private final ObjectMapper mapper;

    WebSocketEventSender(ObjectMapper mapper) { this.mapper = mapper; }

    void send(WebSocketSession socket, Object body) throws IOException {
        synchronized (socket) {
            socket.sendMessage(new TextMessage(mapper.writeValueAsString(body)));
        }
    }
}
