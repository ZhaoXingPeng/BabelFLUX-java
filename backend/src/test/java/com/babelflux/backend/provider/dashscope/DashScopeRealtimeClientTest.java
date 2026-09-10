package com.babelflux.backend.provider.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelflux.backend.config.DashScopeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashScopeRealtimeClientTest {
    @Test
    void sendsSessionUpdateAndNormalizesProviderEvents() {
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");
        FakeConnection connection = new FakeConnection(
                "{\"type\":\"session.updated\"}",
                "{\"type\":\"input_audio_buffer.speech_started\",\"item_id\":\"item-1\"}",
                "{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"item-1\",\"transcript\":\"hello\"}",
                "{\"type\":\"response.audio_transcript.delta\",\"response_id\":\"r-1\",\"delta\":\"你\"}",
                "{\"type\":\"response.audio_transcript.delta\",\"response_id\":\"r-1\",\"delta\":\"好\"}",
                "{\"type\":\"response.audio_transcript.done\",\"response_id\":\"r-1\",\"text\":\"你好\"}");
        DashScopeRealtimeClient client = new DashScopeRealtimeClient(properties, new ObjectMapper(),
                (url, headers) -> connection);
        DashScopeRealtimeClient.LiveSession session = client.connect(new DashScopeRealtimeClient.Request(
                null, "en", "zh", null, false, null, 16_000, Map.of("API", "接口")));

        assertTrue(connection.sent.getFirst().contains("session.update"));
        assertTrue(connection.sent.getFirst().contains("接口"));
        assertEquals("session_ready", session.receive(Duration.ofMillis(1)).kind());
        assertEquals("speech_started", session.receive(Duration.ofMillis(1)).kind());
        assertEquals("source_final", session.receive(Duration.ofMillis(1)).kind());
        assertEquals("你", session.receive(Duration.ofMillis(1)).text());
        assertEquals("你好", session.receive(Duration.ofMillis(1)).text());
        assertEquals("translation_final", session.receive(Duration.ofMillis(1)).kind());
        session.sendAudio(new byte[]{1, 2, 3});
        assertTrue(connection.sent.getLast().contains("input_audio_buffer.append"));
    }

    @Test
    void usesExplicitWebsocketBaseUrlInsteadOfCompatibleHttpBaseUrl() {
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1");
        properties.setWebsocketBaseUrl("wss://dashscope.aliyuncs.com/api-ws/v1/");
        FakeConnection connection = new FakeConnection();
        DashScopeRealtimeClient client = new DashScopeRealtimeClient(properties, new ObjectMapper(),
                (url, headers) -> {
                    assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=test-model", url);
                    return connection;
                });

        client.connect(new DashScopeRealtimeClient.Request(
                "test-model", "en", "zh", null, false, null, 16_000, Map.of()));
    }

    private static final class FakeConnection implements DashScopeRealtimeClient.Connection {
        private final ArrayDeque<String> received = new ArrayDeque<>();
        private final List<String> sent = new ArrayList<>();
        private FakeConnection(String... events) { received.addAll(List.of(events)); }
        @Override public void send(String payload) { sent.add(payload); }
        @Override public String receive(Duration timeout) { return received.pollFirst(); }
        @Override public void close() { }
    }
}
