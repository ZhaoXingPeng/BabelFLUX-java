package com.babelflux.backend.provider.dashscope;

import com.babelflux.backend.config.DashScopeProperties;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DashScopeClient {
    private final DashScopeProperties properties;
    private final RestClient client;

    public DashScopeClient(DashScopeProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = Math.toIntExact(properties.getRequestTimeout().toMillis());
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        this.client = builder.requestFactory(requestFactory).baseUrl(properties.getBaseUrl()).build();
    }

    public Map<?, ?> generate(String model, List<Map<String, String>> messages) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY is required for real model calls");
        }
        var payload = Map.of("model", model, "input", Map.of("messages", messages == null ? List.of() : messages), "parameters", Map.of("result_format", "message"));
        return client.post().uri("/services/aigc/text-generation/generation")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .header("X-DashScope-WorkSpace", properties.getWorkspaceId() == null ? "" : properties.getWorkspaceId())
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(Map.class);
    }
}
