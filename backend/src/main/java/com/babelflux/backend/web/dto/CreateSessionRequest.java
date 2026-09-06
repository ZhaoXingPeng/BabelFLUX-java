package com.babelflux.backend.web.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

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
        Boolean ttsEnabled,
        String sourcePermission,
        List<GlossaryTermRequest> glossary) {
    public CreateSessionRequest {
        glossary = glossary == null ? List.of() : List.copyOf(glossary);
    }

    public record GlossaryTermRequest(String sourceTerm, String targetTerm, Integer priority, String note) {
        public GlossaryTermRequest {
            priority = priority == null ? 0 : priority;
        }
    }
}
