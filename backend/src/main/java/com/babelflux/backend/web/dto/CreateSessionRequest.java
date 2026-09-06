package com.babelflux.backend.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateSessionRequest(
        @Pattern(regexp = "^(demo|url|microphone|browser_audio|screen_window|media_element_audio|system_audio)?$")
        String inputMode,
        @Size(max = 32)
        String sourceLanguage,
        @Size(max = 32)
        String targetLanguage,
        @Pattern(regexp = "^(quick|floating)?$")
        String productMode,
        @Size(max = 120) String sessionName,
        @Size(max = 128)
        String domain,
        @Size(max = 128)
        String modelProfile,
        @Size(max = 128)
        String sourceKey,
        @Size(max = 512)
        String sourceFileName,
        @Size(max = 2048)
        String sourceUrl,
        Boolean ttsEnabled,
        @Pattern(regexp = "^(idle|requesting|granted|denied)?$")
        String sourcePermission,
        List<@Valid GlossaryTermRequest> glossary) {
    public CreateSessionRequest {
        glossary = glossary == null ? List.of() : List.copyOf(glossary);
    }

    public record GlossaryTermRequest(@Size(max = 256) String sourceTerm,
                                      @Size(max = 256) String targetTerm,
                                      Integer priority,
                                      @Size(max = 512) String note) {
        public GlossaryTermRequest {
            priority = priority == null ? 0 : priority;
        }
    }
}
