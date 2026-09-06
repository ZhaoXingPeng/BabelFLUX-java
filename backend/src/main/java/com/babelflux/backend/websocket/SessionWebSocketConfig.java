package com.babelflux.backend.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class SessionWebSocketConfig implements WebSocketConfigurer {
    private final SessionWebSocketHandler handler;
    public SessionWebSocketConfig(SessionWebSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/ws/sessions/{sessionId}")
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }
}
