package com.babelflux.backend.provider.dashscope;

import com.babelflux.backend.config.DashScopeProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Synchronous facade over DashScope's duplex ASR and realtime TTS WebSocket protocols. */
@Component
public class DashScopeSpeechClient {
    private final DashScopeProperties properties;
    private final ObjectMapper mapper;
    private final WebSocketConnector connector;

    @Autowired
    public DashScopeSpeechClient(DashScopeProperties properties, ObjectMapper mapper) {
        this(properties, mapper, new JdkWebSocketConnector());
    }

    DashScopeSpeechClient(DashScopeProperties properties, ObjectMapper mapper, WebSocketConnector connector) {
        this.properties = properties;
        this.mapper = mapper;
        this.connector = connector;
    }

    public AsrResult transcribe(byte[] audio, String model, String audioFormat, int sampleRate) {
        if (audio == null || audio.length == 0) throw new DashScopeClient.InvalidRequestException("audio file is empty");
        String normalizedAudioFormat = audioFormat == null || audioFormat.isBlank() ? "pcm" : audioFormat;
        validateAudioInput(audio, normalizedAudioFormat, sampleRate);
        ensureConfigured();
        String taskId = UUID.randomUUID().toString();
        List<String> events = new ArrayList<>();
        List<AsrSegment> segments = new ArrayList<>();
        Map<String, Object> usage = Map.of();
        String requestId = null;
        try (WebSocketConnection ws = connector.connect(asrUrl(), headers())) {
            ws.sendText(mapper.writeValueAsString(Map.of("header", Map.of("action", "run-task",
                    "task_id", taskId, "streaming", "duplex"), "payload", Map.of("task_group", "audio",
                    "task", "asr", "function", "recognition", "model", model,
                    "parameters", Map.of("format", normalizedAudioFormat, "sample_rate", sampleRate), "input", Map.of()))));
            Message started = waitForHeaderEvent(ws, "task-started", events);
            requestId = firstText(started.node().path("header"), "request_id", "requestId");
            for (int offset = 0; offset < audio.length; offset += 3200) {
                int end = Math.min(audio.length, offset + 3200);
                ws.sendBinary(ByteBuffer.wrap(audio, offset, end - offset));
            }
            ws.sendText(mapper.writeValueAsString(Map.of("header", Map.of("action", "finish-task",
                    "task_id", taskId, "streaming", "duplex"), "payload", Map.of("input", Map.of()))));
            while (true) {
                JsonNode node = receiveJson(ws);
                String event = headerEvent(node);
                events.add(event);
                requestId = requestId == null ? firstText(node.path("header"), "request_id", "requestId") : requestId;
                if ("result-generated".equals(event)) {
                    JsonNode sentence = node.path("payload").path("output").path("sentence");
                    String text = text(sentence, "text");
                    if (text != null && !text.isBlank()) {
                        segments.add(new AsrSegment(text, nullableLong(sentence, "begin_time"),
                                nullableLong(sentence, "end_time"), sentence.path("sentence_end").asBoolean(false)));
                    }
                } else if ("task-finished".equals(event)) {
                    usage = objectMap(node.path("payload").path("usage"));
                    break;
                } else if ("task-failed".equals(event)) {
                    throw providerError(node, "DashScope ASR task failed");
                }
            }
        } catch (DashScopeClient.InvalidRequestException | DashScopeClient.UpstreamException error) {
            throw error;
        } catch (DashScopeClient.TimeoutException error) {
            throw error;
        } catch (Exception error) {
            throw new DashScopeClient.UpstreamException("DashScope ASR connection failed", 502, null,
                    requestId, error);
        }
        String resultText = segments.stream().filter(AsrSegment::isFinal).map(AsrSegment::text)
                .reduce((left, right) -> left + " " + right)
                .orElseGet(() -> segments.isEmpty() ? "" : segments.getLast().text());
        return new AsrResult(requestId, model, resultText, List.copyOf(segments), List.copyOf(events), usage);
    }

    public TtsResult synthesize(String text, String model, String voice, String languageType,
                                String audioFormat, int sampleRate, String mode) {
        if (text == null || text.isBlank()) throw new DashScopeClient.InvalidRequestException("text is required");
        ensureConfigured();
        String normalizedModel = model == null || model.isBlank() ? "qwen3-tts-flash-realtime" : model;
        validateTtsModel(normalizedModel);
        List<String> events = new ArrayList<>();
        java.io.ByteArrayOutputStream audio = new java.io.ByteArrayOutputStream();
        String sessionId = null;
        try (WebSocketConnection ws = connector.connect(ttsUrl(normalizedModel), headers())) {
            ws.sendText(json(Map.of("type", "session.update", "session", Map.of("mode", mode,
                    "voice", voice, "language_type", languageType, "response_format", audioFormat,
                    "sample_rate", sampleRate))));
            // DashScope may acknowledge session.update with session.created only.
            // Treat either lifecycle acknowledgement as a completed handshake;
            // waiting specifically for session.updated turns a healthy socket into a timeout.
            try {
                long handshakeDeadline = System.nanoTime() + speechHandshakeTimeout().toNanos();
                while (true) {
                    long remainingNanos = handshakeDeadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw new DashScopeClient.TimeoutException("TTS handshake deadline exceeded", null);
                    }
                    JsonNode node = receiveJson(ws, Duration.ofNanos(remainingNanos));
                    String event = text(node, "type");
                    events.add(event);
                    if ("session.created".equals(event) || "session.updated".equals(event)) {
                        sessionId = sessionId == null ? text(node.path("session"), "id") : sessionId;
                        break;
                    } else if ("error".equals(event)) throw realtimeError(node, "DashScope TTS session failed");
                }
            } catch (DashScopeClient.TimeoutException timeout) {
                throw new DashScopeClient.TimeoutException(
                        "DashScope TTS handshake timed out; verify model and provider availability", timeout);
            }
            ws.sendText(json(Map.of("type", "input_text_buffer.append", "text", text)));
            if ("commit".equals(mode)) ws.sendText(json(Map.of("type", "input_text_buffer.commit")));
            while (true) {
                JsonNode node = receiveJson(ws);
                String event = text(node, "type");
                events.add(event);
                if ("response.audio.delta".equals(event)) {
                    String delta = text(node, "delta");
                    if (delta != null && !delta.isBlank()) audio.writeBytes(Base64.getDecoder().decode(delta));
                } else if ("response.done".equals(event)) break;
                else if ("error".equals(event)) throw realtimeError(node, "DashScope TTS response failed");
            }
            ws.sendText(json(Map.of("type", "session.finish")));
            while (true) {
                JsonNode node = receiveJson(ws);
                String event = text(node, "type");
                events.add(event);
                if ("session.finished".equals(event)) break;
                if ("error".equals(event)) throw realtimeError(node, "DashScope TTS finish failed");
            }
        } catch (DashScopeClient.InvalidRequestException | DashScopeClient.UpstreamException error) {
            throw error;
        } catch (DashScopeClient.TimeoutException error) {
            throw error;
        } catch (Exception error) {
            throw new DashScopeClient.UpstreamException("DashScope TTS connection failed", 502, null,
                    null, error);
        }
        return new TtsResult(normalizedModel, voice, audio.toByteArray(), audioFormat, sampleRate, List.copyOf(events), sessionId);
    }

    private Message waitForHeaderEvent(WebSocketConnection ws, String expected, List<String> events) throws Exception {
        while (true) {
            JsonNode node = receiveJson(ws);
            String event = headerEvent(node);
            events.add(event);
            if (expected.equals(event)) return new Message(node);
            if ("task-failed".equals(event)) throw providerError(node, "DashScope ASR task failed");
        }
    }

    private JsonNode receiveJson(WebSocketConnection ws) throws Exception {
        return receiveJson(ws, properties.getRequestTimeout());
    }

    private JsonNode receiveJson(WebSocketConnection ws, Duration timeout) throws Exception {
        String raw = ws.receive(timeout);
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException error) {
            throw new DashScopeClient.UpstreamException("DashScope returned invalid WebSocket JSON", 502,
                    null, null, error);
        }
    }

    private void ensureConfigured() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank())
            throw new DashScopeClient.ConfigurationException("DASHSCOPE_API_KEY is required for speech calls");
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank())
            throw new DashScopeClient.ConfigurationException("DASHSCOPE_HTTP_BASE_URL is required for speech calls");
    }

    private void validateTtsModel(String model) {
        if (properties.getAllowedTtsModels() == null || properties.getAllowedTtsModels().isEmpty()) return;
        boolean allowed = properties.getAllowedTtsModels().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .anyMatch(model::equals);
        if (!allowed) {
            throw new DashScopeClient.InvalidRequestException("unsupported TTS model: " + model
                    + "; configure DASHSCOPE_ALLOWED_TTS_MODELS to allow it");
        }
    }

    private static void validateAudioInput(byte[] audio, String audioFormat, int sampleRate) {
        if (sampleRate < 8_000 || sampleRate > 48_000) {
            throw new DashScopeClient.InvalidRequestException("sampleRate must be between 8000 and 48000 Hz");
        }
        if ("pcm".equalsIgnoreCase(audioFormat) && audio.length % 2 != 0) {
            throw new DashScopeClient.InvalidRequestException("PCM audio byte length must be even");
        }
    }

    private Duration speechHandshakeTimeout() {
        Duration configured = properties.getSpeechHandshakeTimeout();
        Duration general = properties.getRequestTimeout();
        if (configured == null || configured.isNegative() || configured.isZero()) configured = Duration.ofSeconds(5);
        if (general == null || general.isNegative() || general.isZero()) return configured;
        return configured.compareTo(general) > 0 ? general : configured;
    }

    private String asrUrl() { return websocketBaseUrl() + "/inference"; }
    private String ttsUrl(String model) { return websocketBaseUrl() + "/realtime?model=" + URLEncoder.encode(model, StandardCharsets.UTF_8); }
    private String websocketBaseUrl() {
        String configured = properties.getWebsocketBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.trim().replaceFirst("^http", "ws").replaceFirst("/+$", "");
        }
        String base = properties.getBaseUrl().replaceFirst("^http", "ws");
        int api = base.indexOf("/api/");
        return (api < 0 ? base : base.substring(0, api)) + "/api-ws/v1";
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + properties.getApiKey());
        if (properties.getWorkspaceId() != null && !properties.getWorkspaceId().isBlank())
            headers.put("X-DashScope-WorkSpace", properties.getWorkspaceId());
        return headers;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("speech request cannot be serialized", error); }
    }

    private static String headerEvent(JsonNode node) { return text(node.path("header"), "event"); }
    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }
    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) { String value = text(node, key); if (value != null && !value.isBlank()) return value; }
        return null;
    }
    private static Long nullableLong(JsonNode node, String key) { return node.hasNonNull(key) ? node.get(key).asLong() : null; }
    private static Map<String, Object> objectMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().isNumber() ? entry.getValue().numberValue() : entry.getValue().asText()));
        return Map.copyOf(map);
    }
    private static DashScopeClient.UpstreamException providerError(JsonNode node, String fallback) {
        JsonNode header = node.path("header");
        return new DashScopeClient.UpstreamException(text(header, "error_message") == null ? fallback : text(header, "error_message"),
                502, firstText(header, "error_code", "code"), firstText(header, "request_id", "requestId"), null);
    }
    private static DashScopeClient.UpstreamException realtimeError(JsonNode node, String fallback) {
        JsonNode error = node.path("error");
        return new DashScopeClient.UpstreamException(text(error, "message") == null ? fallback : text(error, "message"),
                502, text(error, "code"), text(node, "event_id"), null);
    }

    public record AsrSegment(String text, Long startMs, Long endMs, boolean isFinal) {}
    public record AsrResult(String requestId, String model, String text, List<AsrSegment> segments,
                            List<String> events, Map<String, Object> usage) {}
    public record TtsResult(String model, String voice, byte[] audio, String audioFormat, int sampleRate,
                            List<String> events, String sessionId) {}

    private record Message(JsonNode node) {}

    interface WebSocketConnector { WebSocketConnection connect(String url, Map<String, String> headers) throws Exception; }
    interface WebSocketConnection extends AutoCloseable {
        void sendText(String text) throws Exception;
        void sendBinary(ByteBuffer data) throws Exception;
        String receive(Duration timeout) throws Exception;
        @Override void close();
    }

    private static final class JdkWebSocketConnector implements WebSocketConnector {
        @Override public WebSocketConnection connect(String url, Map<String, String> headers) {
            WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
            headers.forEach(builder::header);
            Listener listener = new Listener();
            WebSocket socket = builder.buildAsync(URI.create(url), listener).join();
            socket.request(1);
            return new JdkWebSocketConnection(socket, listener);
        }
    }

    private static final class JdkWebSocketConnection implements WebSocketConnection {
        private final WebSocket socket;
        private final Listener listener;
        private JdkWebSocketConnection(WebSocket socket, Listener listener) {
            this.socket = socket;
            this.listener = listener;
        }
        @Override public void sendText(String text) { socket.sendText(text, true).join(); }
        @Override public void sendBinary(ByteBuffer data) { socket.sendBinary(data, true).join(); }
        @Override public String receive(Duration timeout) throws Exception { return listener.next(timeout); }
        @Override public void close() { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").exceptionally(ignored -> null); }
    }

    private static final class Listener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) { messages.offer(text.toString()); text.setLength(0); }
            webSocket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) { webSocket.request(1); return null; }
        @Override public void onError(WebSocket webSocket, Throwable error) { messages.offer("{\"type\":\"error\",\"error\":{\"message\":\"websocket failure\"}}"); }
        private String next(Duration timeout) throws InterruptedException {
            String value = messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (value == null) throw new DashScopeClient.TimeoutException("DashScope speech request timed out", null);
            return value;
        }
    }
}
