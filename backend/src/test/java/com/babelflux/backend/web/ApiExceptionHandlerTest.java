package com.babelflux.backend.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.babelflux.backend.provider.dashscope.DashScopeClient.UpstreamException;
import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsUnavailableRequestedModelToUnprocessableEntity() {
        var response = handler.providerUpstream(new UpstreamException(
                "Model not found (qwen3-asr-flash-realtime)!", 502, "ModelNotFound", "req-1", null));

        assertEquals(422, response.getStatusCode().value());
        assertEquals("ModelNotFound", response.getBody().get("code"));
        assertEquals("req-1", response.getBody().get("requestId"));
    }

    @Test
    void preservesGatewayClassificationForTransientProviderFailure() {
        var response = handler.providerUpstream(new UpstreamException(
                "DashScope connection failed", 502, null, null, null));

        assertEquals(502, response.getStatusCode().value());
    }
}
