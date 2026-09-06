package com.babelflux.backend.web.dto;

import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        String inputMode,
        String sourceLanguage,
        String targetLanguage,
        String productMode,
        @Size(max = 120) String sessionName,
        String domain,
        String modelProfile,
        String sourceKey,
        String sourceFileName,
        String sourceUrl,
        Boolean ttsEnabled) {}
