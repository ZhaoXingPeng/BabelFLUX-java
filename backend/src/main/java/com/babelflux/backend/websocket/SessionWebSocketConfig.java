package com.babelflux.backend.websocket;

import com.babelflux.backend.config.BabelFluxProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class SessionWebSocketConfig implements WebSocketConfigurer {
    private final SessionWebSocketHandler handler;
    private final BabelFluxProperties properties;

    public SessionWebSocketConfig(SessionWebSocketHandler handler, BabelFluxProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/ws/sessions/{sessionId}")
                .setAllowedOrigins(properties.getCorsOrigins().toArray(String[]::new));
    }
}
