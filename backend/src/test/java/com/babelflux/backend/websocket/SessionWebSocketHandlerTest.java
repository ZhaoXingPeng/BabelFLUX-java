package com.babelflux.backend.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.babelflux.backend.config.BabelFluxProperties;
import com.babelflux.backend.domain.Session;
import com.babelflux.backend.infrastructure.InMemorySessionRepository;
import com.babelflux.backend.messaging.JdbcSessionEventOutbox;
import com.babelflux.backend.messaging.SessionEventFactory;
import com.babelflux.backend.search.ReportIndexingPort;
import com.babelflux.backend.service.RealtimeSessionRunner;
import com.babelflux.backend.service.SessionReportService;
import com.babelflux.backend.service.SessionService;
import com.babelflux.backend.service.SessionTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class SessionWebSocketHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final InMemorySessionRepository repository = new InMemorySessionRepository();
    private final SessionService sessions = new SessionService(repository, new SessionReportService(),
            new BabelFluxProperties(), mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class),
            mock(ReportIndexingPort.class));
    private final SessionTokenService tokens = new SessionTokenService();
    private final RealtimeSessionRunner runner = mock(RealtimeSessionRunner.class);
    private final RealtimeSessionRunner.RunHandle run = mock(RealtimeSessionRunner.RunHandle.class);
    private SessionWebSocketHandler handler;
    private Session session;

    @BeforeEach
    void setUp() {
        session = Session.create("ws-1", "WebSocket test", "en", "zh", "general", "default",
                "quick", "demo", "demo", null, "idle", false, List.of());
        repository.save(session);
        handler = new SessionWebSocketHandler(mapper, sessions, tokens, runner);
    }

    @Test
    void rejectsMissingOrInvalidTokenBeforeOpeningSession() throws Exception {
        WebSocketSession socket = socket("ws-1", null);

        handler.afterConnectionEstablished(socket);

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(runner);
        assertEquals("error", sent(socket).get("type").asText());
    }

    @Test
    void startsRunAndForwardsPcmFrames() throws Exception {
        String token = tokens.issue("ws-1");
        WebSocketSession socket = socket("ws-1", token);
        when(socket.getId()).thenReturn("socket-1");
        when(runner.start(any(Session.class), any())).thenReturn(run);

        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"start_session\"}"));
        byte[] pcm = {1, 2, 3, 4};
        handler.handleBinaryMessage(socket, new BinaryMessage(ByteBuffer.wrap(pcm)));

        verify(runner).start(any(Session.class), any());
        verify(run).acceptAudio(pcm);
        assertEquals("running", session.getStatus());
    }

    @Test
    void rejectsSecondPrimarySocketForSameSession() throws Exception {
        String token = tokens.issue("ws-1");
        WebSocketSession first = socket("ws-1", token);
        WebSocketSession second = socket("ws-1", token);
        when(first.getId()).thenReturn("socket-first");
        when(second.getId()).thenReturn("socket-second");
        when(runner.start(any(Session.class), any())).thenReturn(run);

        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);
        handler.handleTextMessage(first, new TextMessage("{\"type\":\"start_session\"}"));
        handler.handleTextMessage(second, new TextMessage("{\"type\":\"start_session\"}"));

        verify(runner, times(1)).start(any(Session.class), any());
        assertEquals("error", sent(second).get("type").asText());
        assertTrue(sent(second).get("message").asText().contains("其他连接"));
        handler.handleTextMessage(second, new TextMessage("{\"type\":\"stop_session\"}"));
        assertNull(session.getReport(), "a non-owner socket must not finish the primary session");
        assertTrue(sent(second).get("message").asText().contains("其他连接"));
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        handler.afterConnectionClosed(second, CloseStatus.NORMAL);
    }

    @Test
    void forwardsMediaClockAndRejectsInvalidClockValues() throws Exception {
        String token = tokens.issue("ws-1");
        WebSocketSession socket = socket("ws-1", token);
        when(runner.start(any(Session.class), any())).thenReturn(run);

        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"start_session\"}"));
        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"media_clock\",\"playbackMs\":1200,\"sentAudioMs\":1000}"));
        verify(run).updateClientClock(1200L, 1000L);

        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"media_clock\",\"playbackMs\":-1,\"sentAudioMs\":1000}"));
        assertEquals("error", sent(socket).get("type").asText());
    }

    @Test
    void rejectsUnknownInputModeOverrideBeforeStartingRunner() throws Exception {
        String token = tokens.issue("ws-1");
        WebSocketSession socket = socket("ws-1", token);

        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"start_session\",\"inputMode\":\"python_pipeline\"}"));

        assertEquals("error", sent(socket).get("type").asText());
        verifyNoInteractions(runner);
    }

    @Test
    void rejectsAudioBeforeSessionStartInsteadOfDroppingFrame() throws Exception {
        String token = tokens.issue("ws-1");
        WebSocketSession socket = socket("ws-1", token);

        handler.afterConnectionEstablished(socket);
        handler.handleBinaryMessage(socket, new BinaryMessage(ByteBuffer.wrap(new byte[]{1, 2})));

        assertEquals("error", sent(socket).get("type").asText());
        verifyNoInteractions(runner);
    }

    @Test
    void audioEndWithoutActiveRunEmitsPersistedReport() throws Exception {
        String token = tokens.issue("ws-1");
        WebSocketSession socket = socket("ws-1", token);

        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"audio_end\"}"));

        JsonNode report = sent(socket);
        assertEquals("session_report", report.get("type").asText());
        assertEquals(session.getReport().reportId(), report.get("reportId").asText());
        assertEquals("ended", session.getStatus());
    }

    @Test
    void handoffSocketReplaysEventsWithoutStartingAnotherRunner() throws Exception {
        SessionEventHub hub = new SessionEventHub();
        handler = new SessionWebSocketHandler(mapper, sessions, tokens, runner, hub);
        var handoff = tokens.issueHandoff("ws-1", null, "en", "zh", "bilingual");
        String token = tokens.issueHandoffWebSocket("ws-1", handoff.expiresAt());
        WebSocketSession socket = socket("ws-1", token);

        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"start_session\"}"));
        hub.publish("ws-1", Map.of("type", "translation_segment", "segmentId", "s1"));

        var messages = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, timeout(1000).atLeast(2)).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream()
                .anyMatch(message -> message.getPayload().contains("translation_segment")));
        org.mockito.Mockito.verifyNoInteractions(runner);
        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
    }

    private WebSocketSession socket(String sessionId, String token) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("socket-" + sessionId);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/api/sessions/" + sessionId
                + (token == null ? "" : "?token=" + token)));
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        return socket;
    }

    private JsonNode sent(WebSocketSession socket) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        return mapper.readTree(captor.getAllValues().getLast().getPayload());
    }
}
