package com.babelflux.backend.web;

import com.babelflux.backend.service.SessionService.SessionNotFoundException;
import com.babelflux.backend.service.SessionService.ReportNotReadyException;
import com.babelflux.backend.service.SessionService.InvalidSessionRequestException;
import com.babelflux.backend.service.ReportExportService.UnsupportedReportFormatException;
import com.babelflux.backend.service.SessionTokenService.HandoffTokenException;
import com.babelflux.backend.service.SessionTokenService.TokenStateUnavailableException;
import com.babelflux.backend.provider.dashscope.DashScopeClient.ConfigurationException;
import com.babelflux.backend.provider.dashscope.DashScopeClient.InvalidRequestException;
import com.babelflux.backend.provider.dashscope.DashScopeClient.TimeoutException;
import com.babelflux.backend.provider.dashscope.DashScopeClient.UpstreamException;
import com.babelflux.backend.search.ReportSearchUnavailableException;
import com.babelflux.backend.observability.OperationalMetrics;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final OperationalMetrics metrics;

    public ApiExceptionHandler() {
        this(OperationalMetrics.NOOP);
    }

    @Autowired
    public ApiExceptionHandler(OperationalMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(InvalidSessionRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidSession(InvalidSessionRequestException error) {
        return Map.of("detail", error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidRequest(MethodArgumentNotValidException error) {
        String detail = error.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(field -> field.getField() + " " + field.getDefaultMessage())
                .orElse("invalid request");
        return Map.of("detail", detail);
    }

    @ExceptionHandler(SessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(SessionNotFoundException error) { return Map.of("detail", error.getMessage()); }

    @ExceptionHandler(ReportNotReadyException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> reportNotReady(ReportNotReadyException error) { return Map.of("detail", error.getMessage()); }

    @ExceptionHandler(ReportSearchUnavailableException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> reportSearchUnavailable(
            ReportSearchUnavailableException error) {
        observedFailure("elasticsearch", HttpStatus.SERVICE_UNAVAILABLE.value(), error);
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler(UnsupportedReportFormatException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> unsupportedReport(UnsupportedReportFormatException error) { return Map.of("detail", error.getMessage()); }

    @ExceptionHandler(ConfigurationException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> providerConfiguration(ConfigurationException error) {
        observedFailure("provider_configuration", HttpStatus.SERVICE_UNAVAILABLE.value(), error);
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler(TimeoutException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> providerTimeout(TimeoutException error) {
        observedFailure("dashscope_timeout", HttpStatus.GATEWAY_TIMEOUT.value(), error);
        return org.springframework.http.ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> providerRequest(InvalidRequestException error) { return Map.of("detail", error.getMessage()); }

    @ExceptionHandler(UpstreamException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> providerUpstream(UpstreamException error) {
        observedFailure("dashscope_upstream", providerStatus(error).value(), error);
        Map<String, String> detail = new java.util.LinkedHashMap<>();
        detail.put("message", error.getMessage());
        if (error.getCode() != null) detail.put("code", error.getCode());
        if (error.getRequestId() != null) detail.put("requestId", error.getRequestId());
        return org.springframework.http.ResponseEntity.status(providerStatus(error)).body(detail);
    }

    private static HttpStatus providerStatus(UpstreamException error) {
        // The caller can correct an unavailable provider model. Do not present it
        // as a transient gateway failure that clients should retry.
        if ("ModelNotFound".equals(error.getCode())) return HttpStatus.UNPROCESSABLE_ENTITY;
        return HttpStatus.BAD_GATEWAY;
    }

    @ExceptionHandler(HandoffTokenException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handoff(HandoffTokenException error) {
        int status = switch (error.getCode()) {
            case "used" -> 409;
            case "expired" -> 410;
            case "not_found" -> 404;
            default -> 400;
        };
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler(TokenStateUnavailableException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> tokenStateUnavailable(
            TokenStateUnavailableException error) {
        observedFailure("redis", HttpStatus.SERVICE_UNAVAILABLE.value(), error);
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", error.getMessage()));
    }

    private void observedFailure(String category, int status, RuntimeException error) {
        metrics.apiFailure(category, status);
        if (category.startsWith("dashscope")) metrics.dependencyFailure("dashscope");
        else if ("elasticsearch".equals(category)) metrics.dependencyFailure(category);
        log.atWarn()
                .addKeyValue("event", "api.failure")
                .addKeyValue("category", category)
                .addKeyValue("status", status)
                .addKeyValue("error_type", error.getClass().getSimpleName())
                .log("API request failed");
    }
}
