package com.babelflux.backend.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Adds a safe request correlation identifier without retaining query parameters or credentials. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String REQUEST_ID_MDC_KEY = "request_id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private final OperationalMetrics metrics;

    public RequestCorrelationFilter(OperationalMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = safeRequestId(request.getHeader(REQUEST_ID_HEADER));
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long started = System.nanoTime();
        try (MDC.MDCCloseable ignored = MDC.putCloseable(REQUEST_ID_MDC_KEY, requestId)) {
            chain.doFilter(request, response);
            Duration duration = Duration.ofNanos(System.nanoTime() - started);
            String route = route(request);
            metrics.httpRequest(request.getMethod(), route, response.getStatus(), duration);
            log.atInfo()
                    .addKeyValue("event", "http.request.completed")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("route", route)
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("duration_ms", duration.toMillis())
                    .log("HTTP request completed");
        }
    }

    static String safeRequestId(String candidate) {
        return candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()
                ? candidate : UUID.randomUUID().toString();
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String value ? value : "unmatched";
    }
}
