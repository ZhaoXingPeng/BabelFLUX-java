package com.babelflux.backend.web;

import com.babelflux.backend.service.SessionService.SessionNotFoundException;
import com.babelflux.backend.service.SessionService.ReportNotReadyException;
import com.babelflux.backend.service.ReportExportService.UnsupportedReportFormatException;
import com.babelflux.backend.service.SessionTokenService.HandoffTokenException;
import com.babelflux.backend.service.SessionTokenService.TokenStateUnavailableException;
import com.babelflux.backend.provider.dashscope.DashScopeClient.ConfigurationException;
import com.babelflux.backend.provider.dashscope.DashScopeClient.InvalidRequestException;
import com.babelflux.backend.provider.dashscope.DashScopeClient.TimeoutException;
import com.babelflux.backend.provider.dashscope.DashScopeClient.UpstreamException;
import com.babelflux.backend.search.ReportSearchUnavailableException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(SessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(SessionNotFoundException error) { return Map.of("detail", error.getMessage()); }

    @ExceptionHandler(ReportNotReadyException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> reportNotReady(ReportNotReadyException error) { return Map.of("detail", error.getMessage()); }

    @ExceptionHandler(ReportSearchUnavailableException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> reportSearchUnavailable(
            ReportSearchUnavailableException error) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler(UnsupportedReportFormatException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> unsupportedReport(UnsupportedReportFormatException error) { return Map.of("detail", error.getMessage()); }

    @ExceptionHandler(ConfigurationException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> providerConfiguration(ConfigurationException error) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler(TimeoutException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> providerTimeout(TimeoutException error) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> providerRequest(InvalidRequestException error) { return Map.of("detail", error.getMessage()); }

    @ExceptionHandler(UpstreamException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> providerUpstream(UpstreamException error) {
        Map<String, String> detail = new java.util.LinkedHashMap<>();
        detail.put("message", error.getMessage());
        if (error.getCode() != null) detail.put("code", error.getCode());
        if (error.getRequestId() != null) detail.put("requestId", error.getRequestId());
        return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(detail);
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
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", error.getMessage()));
    }
}
