package com.babelflux.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class RequestCorrelationFilterTest {
    @Test
    void returnsSafeCorrelationIdAndRecordsRouteTemplateWithoutQueryValues() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestCorrelationFilter filter = new RequestCorrelationFilter(new MicrometerOperationalMetrics(registry));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/private-session-id");
        request.setQueryString("token=never-record-this");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "request-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/sessions/{id}");
            response.setStatus(503);
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo("request-42");
        assertThat(MDC.get("request_id")).isNull();
        assertThat(registry.get("babelflux.http.server.requests")
                .tags("method", "GET", "route", "/api/sessions/{id}", "status", "503",
                        "outcome", "server_error")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void replacesUnsafeClientProvidedCorrelationId() {
        String requestId = RequestCorrelationFilter.safeRequestId("contains spaces and a token");

        assertThat(requestId).matches("[0-9a-f-]{36}");
    }
}
