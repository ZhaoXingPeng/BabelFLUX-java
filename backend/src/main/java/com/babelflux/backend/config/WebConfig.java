package com.babelflux.backend.config;

import com.babelflux.backend.service.SessionTokenService;
import com.babelflux.backend.observability.OperationalMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final BabelFluxProperties properties;
    private final SessionTokenService tokens;
    private final OperationalMetrics metrics;

    @Autowired
    public WebConfig(BabelFluxProperties properties, SessionTokenService tokens, OperationalMetrics metrics) {
        this.properties = properties;
        this.tokens = tokens;
        this.metrics = metrics;
    }

    public WebConfig(BabelFluxProperties properties, SessionTokenService tokens) {
        this(properties, tokens, OperationalMetrics.NOOP);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.getCorsOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ModelGatewayAuthInterceptor(properties, tokens, metrics))
                .addPathPatterns("/api/models/**");
    }

    static final class ModelGatewayAuthInterceptor implements HandlerInterceptor {
        private final BabelFluxProperties properties;
        private final SessionTokenService tokens;
        private final OperationalMetrics metrics;

        ModelGatewayAuthInterceptor(BabelFluxProperties properties, SessionTokenService tokens) {
            this(properties, tokens, OperationalMetrics.NOOP);
        }

        ModelGatewayAuthInterceptor(BabelFluxProperties properties, SessionTokenService tokens,
                                    OperationalMetrics metrics) {
            this.properties = properties;
            this.tokens = tokens;
            this.metrics = metrics;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws IOException {
            if (!properties.isRequireModelGatewayAuth()) return true;
            String candidate = request.getParameter("token");
            if (candidate == null || candidate.isBlank()) candidate = bearer(request.getHeader("Authorization"));
            try {
                if (tokens.validAny(candidate)) return true;
            } catch (SessionTokenService.TokenStateUnavailableException error) {
                metrics.apiFailure("token_state", HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "model gateway token state unavailable");
                return false;
            }
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "model gateway authentication required");
            return false;
        }

        private static String bearer(String authorization) {
            if (authorization == null) return null;
            int separator = authorization.indexOf(' ');
            if (separator <= 0 || !"bearer".equalsIgnoreCase(authorization.substring(0, separator))) return null;
            String token = authorization.substring(separator + 1).trim();
            return token.isBlank() ? null : token;
        }

        private static void write(HttpServletResponse response, int status, String detail) throws IOException {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"detail\":\"" + detail + "\"}");
        }
    }
}
