package com.babelflux.backend.provider.dashscope;

import com.babelflux.backend.config.DashScopeProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Provider-isolated client for the duplex LiveTranslate WebSocket protocol. */
@Component
public class DashScopeRealtimeClient {
    private final DashScopeProperties properties;
    private final ObjectMapper mapper;
    private final Connector connector;

    @Autowired
    public DashScopeRealtimeClient(DashScopeProperties properties, ObjectMapper mapper) {
        this(properties, mapper, new JdkConnector());
    }

    DashScopeRealtimeClient(DashScopeProperties properties, ObjectMapper mapper, Connector connector) {
        this.properties = properties;
        this.mapper = mapper;
        this.connector = connector;
    }

    public LiveSession connect(Request request) {
        ensureConfigured();
        try {
            Connection connection = connector.connect(websocketUrl(request.model()), headers());
            LiveSession session = new LiveSession(connection, request);
            session.send(sessionUpdate(request));
            return session;
        } catch (DashScopeClient.ConfigurationException | DashScopeClient.UpstreamException error) {
            throw error;
        } catch (Exception error) {
            throw new DashScopeClient.UpstreamException("DashScope realtime connection failed", 502,
                    null, null, error);
        }
    }

    private void ensureConfigured() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank())
            throw new DashScopeClient.ConfigurationException("DASHSCOPE_API_KEY is required for realtime calls");
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank())
            throw new DashScopeClient.ConfigurationException("DASHSCOPE_HTTP_BASE_URL is required for realtime calls");
    }

    private String websocketUrl(String model) {
        return websocketBaseUrl() + "/realtime?model="
                + URLEncoder.encode(model, StandardCharsets.UTF_8);
    }

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

    private Map<String, Object> sessionUpdate(Request request) {
        Map<String, Object> translation = new LinkedHashMap<>();
        translation.put("language", request.targetLanguage());
        if (!request.glossary().isEmpty()) translation.put("corpus", Map.of("phrases", request.glossary()));
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("modalities", request.ttsEnabled() ? List.of("text", "audio") : List.of("text"));
        session.put("input_audio_format", "pcm");
        session.put("sample_rate", request.sampleRate());
        session.put("output_audio_format", "pcm");
        session.put("input_audio_transcription", Map.of("model", request.asrModel(),
                "language", request.sourceLanguage()));
        session.put("translation", translation);
        if (request.ttsEnabled()) session.put("voice", request.voice());
        return Map.of("type", "session.update", "session", session);
    }

    public record Request(String model, String sourceLanguage, String targetLanguage, String asrModel,
                          boolean ttsEnabled, String voice, int sampleRate, Map<String, String> glossary) {
        public Request {
            model = value(model, "qwen3.5-livetranslate-flash-realtime");
            sourceLanguage = value(sourceLanguage, "en");
            targetLanguage = value(targetLanguage, "zh");
            asrModel = value(asrModel, "qwen3-asr-flash-realtime");
            voice = value(voice, "Tina");
            sampleRate = sampleRate < 1 ? 16_000 : sampleRate;
            glossary = glossary == null ? Map.of() : Map.copyOf(glossary);
        }
    }

    public record NormalizedEvent(String kind, String text, byte[] audio,
                                  String itemId, String responseId, Map<String, Object> raw) {
        static NormalizedEvent of(String kind, String text, byte[] audio,
                                  String itemId, String responseId, Map<String, Object> raw) {
            return new NormalizedEvent(kind, text == null ? "" : text, audio == null ? new byte[0] : audio,
                    itemId, responseId, raw == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(raw)));
        }
    }

    public final class LiveSession implements AutoCloseable {
        private final Connection connection;
        private final Request request;
        private final Map<String, String> audioTranscript = new LinkedHashMap<>();

        private LiveSession(Connection connection, Request request) {
            this.connection = connection;
            this.request = request;
        }

        public void sendAudio(byte[] pcm) {
            if (pcm == null || pcm.length == 0) return;
            send(Map.of("type", "input_audio_buffer.append",
                    "audio", Base64.getEncoder().encodeToString(pcm)));
        }

        public void sendSilence(Duration duration) {
            long bytes = Math.max(0L, duration.toMillis()) * request.sampleRate() * 2L / 1000L;
            int chunk = 16_000;
            while (bytes > 0) {
                int size = (int) Math.min(bytes, chunk);
                sendAudio(new byte[size]);
                bytes -= size;
            }
        }

        public void finish() { send(Map.of("type", "session.finish")); }

        public NormalizedEvent receive(Duration timeout) {
            String payload = connection.receive(timeout);
            if (payload == null) return null;
            try {
                JsonNode node = mapper.readTree(payload);
                return normalize(node);
            } catch (JsonProcessingException error) {
                throw new DashScopeClient.UpstreamException("DashScope returned invalid realtime JSON", 502,
                        null, null, error);
            }
        }

        private NormalizedEvent normalize(JsonNode node) {
            String type = text(node, "type");
            String itemId = text(node, "item_id");
            String responseId = text(node, "response_id");
            Map<String, Object> raw = mapper.convertValue(node, Map.class);
            if ("session.created".equals(type) || "session.updated".equals(type))
                return NormalizedEvent.of("session_ready", "", null, itemId, responseId, raw);
            if ("session.finished".equals(type)) return NormalizedEvent.of("session_finished", "", null, itemId, responseId, raw);
            if ("input_audio_buffer.speech_started".equals(type))
                return NormalizedEvent.of("speech_started", "", null, itemId, responseId, raw);
            if ("input_audio_buffer.speech_stopped".equals(type))
                return NormalizedEvent.of("speech_stopped", "", null, itemId, responseId, raw);
            if ("conversation.item.input_audio_transcription.text".equals(type))
                return NormalizedEvent.of("source_partial", first(node, "stash", "text"), null, itemId, responseId, raw);
            if ("conversation.item.input_audio_transcription.completed".equals(type))
                return NormalizedEvent.of("source_final", text(node, "transcript"), null, itemId, responseId, raw);
            if ("response.created".equals(type)) {
                JsonNode response = node.path("response");
                String id = response.path("id").asText(null);
                return NormalizedEvent.of("response_created", "", null, itemId, id == null ? responseId : id, raw);
            }
            if ("response.text.text".equals(type))
                return NormalizedEvent.of("translation_partial", first(node, "text", "delta"), null, itemId, responseId, raw);
            if ("response.audio_transcript.text".equals(type) || "response.audio_transcript.delta".equals(type)) {
                String value = first(node, "text", "delta", "transcript");
                String merged = mergeAudioTranscript(responseId, value, "response.audio_transcript.delta".equals(type));
                return NormalizedEvent.of("translation_partial", merged, null, itemId, responseId, raw);
            }
            if ("response.text.done".equals(type) || "response.audio_transcript.done".equals(type)) {
                if (responseId != null) audioTranscript.remove(responseId);
                return NormalizedEvent.of("translation_final", first(node, "text", "transcript"), null, itemId, responseId, raw);
            }
            if ("response.audio.delta".equals(type)) {
                byte[] audio = decode(text(node, "delta"));
                return NormalizedEvent.of("audio", "", audio, itemId, responseId, raw);
            }
            if ("response.done".equals(type)) {
                JsonNode response = node.path("response");
                String id = response.path("id").asText(null);
                return NormalizedEvent.of("response_done", "", null, itemId, id == null ? responseId : id, raw);
            }
            if ("error".equals(type)) {
                JsonNode error = node.path("error");
                return NormalizedEvent.of("error", error.path("message").asText("LiveTranslate error"),
                        null, itemId, responseId, raw);
            }
            return null;
        }

        private String mergeAudioTranscript(String responseId, String value, boolean delta) {
            if (value == null || value.isBlank() || responseId == null) return value == null ? "" : value;
            String previous = audioTranscript.getOrDefault(responseId, "");
            String merged = delta ? previous + value
                    : (value.startsWith(previous) || previous.contains(value) ? value : previous + value);
            audioTranscript.put(responseId, merged);
            return merged;
        }

        private void send(Map<String, Object> value) {
            Map<String, Object> event = new LinkedHashMap<>(value);
            event.put("event_id", "event_" + UUID.randomUUID());
            try {
                connection.send(mapper.writeValueAsString(event));
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("realtime request cannot be serialized", error);
            } catch (Exception error) {
                throw new DashScopeClient.UpstreamException("DashScope realtime send failed", 502,
                        null, null, error);
            }
        }

        @Override public void close() { connection.close(); }
    }

    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
    private static String first(JsonNode node, String... fields) {
        for (String field : fields) { String value = text(node, field); if (value != null && !value.isBlank()) return value; }
        return "";
    }
    private static byte[] decode(String value) {
        if (value == null || value.isBlank()) return new byte[0];
        try { return Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException error) { return new byte[0]; }
    }

    interface Connector { Connection connect(String url, Map<String, String> headers) throws Exception; }
    interface Connection {
        void send(String payload) throws Exception;
        String receive(Duration timeout);
        void close();
    }

    private static final class JdkConnector implements Connector {
        @Override public Connection connect(String url, Map<String, String> headers) {
            WebSocket.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .build().newWebSocketBuilder();
            headers.forEach(builder::header);
            Listener listener = new Listener();
            WebSocket socket = builder.buildAsync(URI.create(url), listener).join();
            socket.request(1);
            return new JdkConnection(socket, listener);
        }
    }

    private static final class JdkConnection implements Connection {
        private final WebSocket socket;
        private final Listener listener;
        private JdkConnection(WebSocket socket, Listener listener) { this.socket = socket; this.listener = listener; }
        @Override public synchronized void send(String payload) { socket.sendText(payload, true).join(); }
        @Override public String receive(Duration timeout) { return listener.next(timeout); }
        @Override public void close() { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").exceptionally(ignored -> null); }
    }

    private static final class Listener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) { messages.offer(text.toString()); text.setLength(0); }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            socket.request(1);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) {
            messages.offer("{\"type\":\"error\",\"error\":{\"message\":\"websocket failure\"}}");
        }
        private String next(Duration timeout) {
            try { return messages.poll(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return null; }
        }
    }
}
