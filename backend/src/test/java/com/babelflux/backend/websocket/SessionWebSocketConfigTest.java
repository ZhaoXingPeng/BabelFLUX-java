package com.babelflux.backend.websocket;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.babelflux.backend.config.BabelFluxProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

class SessionWebSocketConfigTest {
    @Test
    void reusesTheConfiguredExactCorsOriginAllowlist() {
        SessionWebSocketHandler handler = mock(SessionWebSocketHandler.class);
        BabelFluxProperties properties = new BabelFluxProperties();
        properties.setCorsOrigins(List.of("https://babelflux.icu", "http://localhost:5173"));
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(eq(handler), eq("/api/ws/sessions/{sessionId}"))).thenReturn(registration);

        new SessionWebSocketConfig(handler, properties).registerWebSocketHandlers(registry);

        verify(registration).setAllowedOrigins("https://babelflux.icu", "http://localhost:5173");
    }
}
