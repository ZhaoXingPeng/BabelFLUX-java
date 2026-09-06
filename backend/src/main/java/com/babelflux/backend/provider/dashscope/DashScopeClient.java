package com.babelflux.backend.provider.dashscope;

import com.babelflux.backend.config.DashScopeProperties;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class DashScopeClient {
    private final DashScopeProperties properties;
    private final RestClient client;

    @Autowired
    public DashScopeClient(DashScopeProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = Math.toIntExact(properties.getRequestTimeout().toMillis());
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        this.client = builder.requestFactory(requestFactory).baseUrl(properties.getBaseUrl()).build();
    }

    public DashScopeClient(DashScopeProperties properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    public LlmGenerateResponse generate(String model, String endpoint,
                                        List<Map<String, Object>> messages,
                                        Map<String, Object> parameters) {
        ensureConfigured();
        String normalizedEndpoint = endpoint == null || endpoint.isBlank() ? "multimodal" : endpoint;
        if (!normalizedEndpoint.equals("text") && !normalizedEndpoint.equals("multimodal")) {
            throw new InvalidRequestException("unsupported generation endpoint: " + normalizedEndpoint);
        }

        List<Map<String, Object>> normalizedMessages = normalizeMessages(messages, normalizedEndpoint);
        Map<String, Object> payload = properties.isOpenAiCompatible()
                ? compatiblePayload(model, normalizedMessages, parameters)
                : dashScopePayload(model, normalizedMessages, parameters, normalizedEndpoint);
        try {
            Map<?, ?> data = client.post().uri(properties.isOpenAiCompatible()
                            ? "/chat/completions" : nativePath(normalizedEndpoint))
                    .headers(this::applyHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            return normalizeResponse(data, model);
        } catch (ResourceAccessException error) {
            if (hasCause(error, SocketTimeoutException.class)) {
                throw new TimeoutException("DashScope request timed out", error);
            }
            throw new UpstreamException("DashScope connection failed", 502, null, null, error);
        } catch (HttpStatusCodeException error) {
            throw upstream(error);
        } catch (RestClientResponseException error) {
            throw upstream(error);
        }
    }

    private void ensureConfigured() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ConfigurationException("DASHSCOPE_API_KEY is required for real model calls");
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new ConfigurationException("DASHSCOPE_HTTP_BASE_URL is required for model calls");
        }
    }

    private void applyHeaders(HttpHeaders headers) {
        headers.setBearerAuth(properties.getApiKey());
        if (properties.getWorkspaceId() != null && !properties.getWorkspaceId().isBlank()) {
            headers.set("X-DashScope-WorkSpace", properties.getWorkspaceId());
        }
    }

    private static String nativePath(String endpoint) {
        return endpoint.equals("text")
                ? "/services/aigc/text-generation/generation"
                : "/services/aigc/multimodal-generation/generation";
    }

    private static Map<String, Object> dashScopePayload(String model,
                                                         List<Map<String, Object>> messages,
                                                         Map<String, Object> parameters,
                                                         String endpoint) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (parameters != null) options.putAll(parameters);
        if (endpoint.equals("text")) options.putIfAbsent("result_format", "message");
        return Map.of("model", model, "input", Map.of("messages", messages), "parameters", options);
    }

    private static Map<String, Object> compatiblePayload(String model,
                                                          List<Map<String, Object>> messages,
                                                          Map<String, Object> parameters) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", false);
        if (parameters != null) payload.putAll(parameters);
        return payload;
    }

    private static List<Map<String, Object>> normalizeMessages(List<Map<String, Object>> messages,
                                                                String endpoint) {
        if (messages == null) return List.of();
        List<Map<String, Object>> normalized = new ArrayList<>(messages.size());
        for (Map<String, Object> message : messages) {
            if (message == null) continue;
            Map<String, Object> copy = new LinkedHashMap<>(message);
            Object content = copy.get("content");
            if (endpoint.equals("multimodal") && content instanceof String text) {
                copy.put("content", List.of(Map.of("text", text)));
            }
            normalized.add(copy);
        }
        return List.copyOf(normalized);
    }

    private static LlmGenerateResponse normalizeResponse(Map<?, ?> data, String requestedModel) {
        Map<?, ?> root = data == null ? Map.of() : data;
        Object outputValue = root.get("output");
        Map<?, ?> output = outputValue instanceof Map<?, ?> value ? value : Map.of();
        Object choicesValue = output.get("choices");
        if (!(choicesValue instanceof List<?>)) choicesValue = root.get("choices");
        List<?> choices = choicesValue instanceof List<?> value ? value : List.of();
        Map<?, ?> choice = choices.isEmpty() || !(choices.getFirst() instanceof Map<?, ?> value)
                ? Map.of() : value;
        Object messageValue = choice.get("message");
        Map<?, ?> message = messageValue instanceof Map<?, ?> value ? value : Map.of();
        Object contentValue = message.get("content");
        if (contentValue == null) contentValue = output.get("text");
        if (contentValue == null) contentValue = root.get("text");

        List<Map<String, Object>> contentParts = new ArrayList<>();
        String content;
        if (contentValue instanceof String text) {
            content = text;
            if (!text.isEmpty()) contentParts.add(Map.of("text", text));
        } else if (contentValue instanceof List<?> parts) {
            StringBuilder joined = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                    contentParts.add(normalized);
                    Object text = map.get("text");
                    if (text != null) joined.append(text);
                }
            }
            content = joined.toString();
        } else {
            content = contentValue == null ? "" : String.valueOf(contentValue);
        }

        String requestId = firstString(root, "request_id", "requestId", "id");
        String model = firstString(root, "model");
        if (model == null || model.isBlank()) model = requestedModel;
        String finishReason = firstString(choice, "finish_reason", "finishReason");
        Map<String, Object> usage = mapValue(root.get("usage"));
        return new LlmGenerateResponse(requestId, model, content, List.copyOf(contentParts), finishReason, usage);
    }

    private static String firstString(Map<?, ?> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Map.copyOf(result);
    }

    private static UpstreamException upstream(RestClientResponseException error) {
        String code = null;
        String requestId = null;
        try {
            JsonNode body = new ObjectMapper().readTree(error.getResponseBodyAsString());
            code = text(body, "code");
            requestId = text(body, "request_id");
            requestId = requestId == null ? text(body, "requestId") : requestId;
        } catch (Exception ignored) {
            // Some gateways return an empty or non-JSON body; status is still actionable.
        }
        return new UpstreamException("DashScope request failed (HTTP " + error.getStatusCode().value() + ")",
                error.getStatusCode().value(), code, requestId, error);
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    public record LlmGenerateResponse(String requestId, String model, String content,
                                      List<Map<String, Object>> contentParts,
                                      String finishReason, Map<String, Object> usage) {}

    public static class ConfigurationException extends RuntimeException {
        public ConfigurationException(String message) { super(message); }
    }

    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) { super(message); }
    }

    public static class TimeoutException extends RuntimeException {
        public TimeoutException(String message, Throwable cause) { super(message, cause); }
    }

    public static class UpstreamException extends RuntimeException {
        private final int status;
        private final String code;
        private final String requestId;

        public UpstreamException(String message, int status, String code, String requestId, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.code = code;
            this.requestId = requestId;
        }

        public int getStatus() { return status; }
        public String getCode() { return code; }
        public String getRequestId() { return requestId; }
    }
}
