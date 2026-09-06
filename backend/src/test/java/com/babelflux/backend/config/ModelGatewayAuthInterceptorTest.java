package com.babelflux.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelflux.backend.service.SessionTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ModelGatewayAuthInterceptorTest {
    @Test
    void rejectsAnonymousModelCallsWhenProtectionIsEnabled() throws Exception {
        BabelFluxProperties properties = new BabelFluxProperties();
        properties.setRequireModelGatewayAuth(true);
        var interceptor = new WebConfig.ModelGatewayAuthInterceptor(properties, new SessionTokenService());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("authentication required"));
    }

    @Test
    void acceptsSessionTokenFromQueryOrBearerHeader() throws Exception {
        BabelFluxProperties properties = new BabelFluxProperties();
        properties.setRequireModelGatewayAuth(true);
        SessionTokenService tokens = new SessionTokenService();
        String token = tokens.issue("session-1");
        var interceptor = new WebConfig.ModelGatewayAuthInterceptor(properties, tokens);

        MockHttpServletRequest query = new MockHttpServletRequest();
        query.setParameter("token", token);
        assertTrue(interceptor.preHandle(query, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest header = new MockHttpServletRequest();
        header.addHeader("Authorization", "Bearer " + token);
        assertTrue(interceptor.preHandle(header, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void disabledProtectionDoesNotRequireAConfiguredToken() throws Exception {
        var interceptor = new WebConfig.ModelGatewayAuthInterceptor(new BabelFluxProperties(),
                new SessionTokenService());
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
    }
}
