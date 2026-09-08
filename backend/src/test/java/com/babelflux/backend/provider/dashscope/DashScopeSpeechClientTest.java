package com.babelflux.backend.provider.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.babelflux.backend.config.DashScopeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class DashScopeSpeechClientTest {
    @Test
    void transcribesDuplexAudioAndNormalizesFinalSegments() {
        FakeConnection connection = new FakeConnection(
                "{\"header\":{\"event\":\"task-started\",\"request_id\":\"asr-1\"}}",
                "{\"header\":{\"event\":\"result-generated\"},\"payload\":{\"output\":{\"sentence\":{\"text\":\"hello\",\"begin_time\":0,\"end_time\":900,\"sentence_end\":true}}}}",
                "{\"header\":{\"event\":\"task-finished\"},\"payload\":{\"usage\":{\"duration\":1}}}");
        DashScopeSpeechClient client = new DashScopeSpeechClient(properties(), new ObjectMapper(),
                (url, headers) -> connection);

        var result = client.transcribe(new byte[6400], "fun-asr-realtime", "pcm", 16_000);

        assertEquals("asr-1", result.requestId());
        assertEquals("hello", result.text());
        assertEquals(1, result.segments().size());
        assertEquals(0L, result.segments().getFirst().startMs());
        assertEquals(java.util.List.of("task-started", "result-generated", "task-finished"), result.events());
        assertTrue(connection.binaryBytes > 0);
        assertTrue(connection.sentText.getFirst().contains("run-task"));
    }

    @Test
    void synthesizesRealtimeAudioAndSendsFinishLifecycle() {
        String audio = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        FakeConnection connection = new FakeConnection(
                "{\"type\":\"session.created\",\"session\":{\"id\":\"tts-1\"}}",
                "{\"type\":\"session.updated\",\"session\":{\"id\":\"tts-1\"}}",
                "{\"type\":\"response.audio.delta\",\"delta\":\"" + audio + "\"}",
                "{\"type\":\"response.done\"}",
                "{\"type\":\"session.finished\"}");
        DashScopeSpeechClient client = new DashScopeSpeechClient(properties(), new ObjectMapper(),
                (url, headers) -> connection);

        var result = client.synthesize("hello", "qwen3-tts-flash-realtime", "Cherry", "Auto", "pcm", 24_000, "commit");

        assertEquals("tts-1", result.sessionId());
        assertArrayEquals(new byte[]{1, 2, 3}, result.audio());
        assertTrue(connection.sentText.stream().anyMatch(value -> value.contains("input_text_buffer.commit")));
        assertTrue(connection.sentText.stream().anyMatch(value -> value.contains("session.finish")));
    }

    @Test
    void treatsSessionCreatedAsCompletedTtsHandshake() {
        String audio = Base64.getEncoder().encodeToString(new byte[]{4, 5});
        FakeConnection connection = new FakeConnection(
                "{\"type\":\"session.created\",\"session\":{\"id\":\"created-only\"}}",
                "{\"type\":\"response.audio.delta\",\"delta\":\"" + audio + "\"}",
                "{\"type\":\"response.done\"}",
                "{\"type\":\"session.finished\"}");
        DashScopeSpeechClient client = new DashScopeSpeechClient(properties(), new ObjectMapper(),
                (url, headers) -> connection);

        var result = client.synthesize("hello", "qwen3-tts-flash-realtime", "Cherry", "Auto", "pcm", 24_000, "commit");

        assertEquals("created-only", result.sessionId());
        assertArrayEquals(new byte[]{4, 5}, result.audio());
        assertEquals(java.util.List.of("session.created", "response.audio.delta", "response.done", "session.finished"),
                result.events());
    }

    private static DashScopeProperties properties() {
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");
        properties.setRequestTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private static final class FakeConnection implements DashScopeSpeechClient.WebSocketConnection {
        private final ArrayDeque<String> inbound = new ArrayDeque<>();
        private final ArrayList<String> sentText = new ArrayList<>();
        private int binaryBytes;

        private FakeConnection(String... messages) { java.util.Collections.addAll(inbound, messages); }
        @Override public void sendText(String text) { sentText.add(text); }
        @Override public void sendBinary(ByteBuffer data) { binaryBytes += data.remaining(); }
        @Override public String receive(Duration timeout) { return inbound.removeFirst(); }
        @Override public void close() {}
    }
}
