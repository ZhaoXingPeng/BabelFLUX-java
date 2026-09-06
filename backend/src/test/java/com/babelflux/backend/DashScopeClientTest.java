package com.babelflux.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.babelflux.backend.config.DashScopeProperties;
import com.babelflux.backend.provider.dashscope.DashScopeClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

class DashScopeClientTest {
    @Test
    void sendsNativeTextRequestAndNormalizesResponse() {
        DashScopeProperties properties = properties("http://dashscope.test/api/v1");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeClient client = new DashScopeClient(properties, builder.baseUrl(properties.getBaseUrl()).build());
        server.expect(requestTo("http://dashscope.test/api/v1/services/aigc/text-generation/generation"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().json("{\"model\":\"qwen-plus\",\"input\":{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]},\"parameters\":{\"temperature\":0.2,\"result_format\":\"message\"}}"))
                .andRespond(withSuccess("{\"request_id\":\"req-1\",\"output\":{\"choices\":[{\"message\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}]},\"usage\":{\"total_tokens\":3}}", APPLICATION_JSON));

        DashScopeClient.LlmGenerateResponse result = client.generate("qwen-plus", "text",
                List.of(Map.of("role", "user", "content", "hello")), Map.of("temperature", 0.2));

        assertEquals("req-1", result.requestId());
        assertEquals("qwen-plus", result.model());
        assertEquals("你好", result.content());
        assertEquals("stop", result.finishReason());
        assertEquals(3, result.usage().get("total_tokens"));
        server.verify();
    }

    @Test
    void usesOpenAiCompatiblePathAndPreservesContentParts() {
        DashScopeProperties properties = properties("http://provider.test/compatible-mode/v1");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeClient client = new DashScopeClient(properties, builder.baseUrl(properties.getBaseUrl()).build());
        server.expect(requestTo("http://provider.test/compatible-mode/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"hello\"}]}],\"stream\":false,\"temperature\":0.1}"))
                .andRespond(withSuccess("{\"id\":\"chat-1\",\"model\":\"qwen-plus\",\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]},\"finish_reason\":\"stop\"}]}", APPLICATION_JSON));

        DashScopeClient.LlmGenerateResponse result = client.generate("qwen-plus", "multimodal",
                List.of(Map.of("role", "user", "content", "hello")), Map.of("temperature", 0.1));

        assertEquals("chat-1", result.requestId());
        assertEquals("ok", result.content());
        assertEquals(1, result.contentParts().size());
        server.verify();
    }

    @Test
    void rejectsMissingCredentialsAndUnsupportedEndpoint() {
        DashScopeProperties properties = properties("http://dashscope.test/api/v1");
        properties.setApiKey("");
        DashScopeClient client = new DashScopeClient(properties, RestClient.builder());
        assertThrows(DashScopeClient.ConfigurationException.class,
                () -> client.generate("qwen-plus", "text", List.of(), Map.of()));

        properties.setApiKey("test-key");
        assertThrows(DashScopeClient.InvalidRequestException.class,
                () -> client.generate("qwen-plus", "stream", List.of(), Map.of()));
    }

    @Test
    void mapsProviderHttpFailureToUpstreamException() {
        DashScopeProperties properties = properties("http://dashscope.test/api/v1");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeClient client = new DashScopeClient(properties, builder.baseUrl(properties.getBaseUrl()).build());
        server.expect(requestTo("http://dashscope.test/api/v1/services/aigc/text-generation/generation"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"code\":\"InvalidApiKey\",\"request_id\":\"req-error\"}")
                        .contentType(APPLICATION_JSON));

        DashScopeClient.UpstreamException error = assertThrows(DashScopeClient.UpstreamException.class,
                () -> client.generate("qwen-plus", "text", List.of(), Map.of()));
        assertEquals(401, error.getStatus());
        assertEquals("InvalidApiKey", error.getCode());
        assertEquals("req-error", error.getRequestId());
        server.verify();
    }

    private static DashScopeProperties properties(String baseUrl) {
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl(baseUrl);
        return properties;
    }
}
