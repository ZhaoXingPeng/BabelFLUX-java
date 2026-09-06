package com.babelflux.backend.web.dto;

public record CreateSessionResponse(String sessionId, String wsToken, String status) {}
