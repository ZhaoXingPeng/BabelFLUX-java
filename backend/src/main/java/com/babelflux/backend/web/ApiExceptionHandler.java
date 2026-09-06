package com.babelflux.backend.web;

import com.babelflux.backend.service.SessionService.SessionNotFoundException;
import com.babelflux.backend.service.SessionTokenService.HandoffTokenException;
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
}
