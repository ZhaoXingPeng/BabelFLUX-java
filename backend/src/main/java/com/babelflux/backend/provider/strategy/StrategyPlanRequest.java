package com.babelflux.backend.provider.strategy;

import java.util.List;

public record StrategyPlanRequest(String sourceLanguage, String targetLanguage, String domain,
                                  Boolean ttsEnabled, String providerPreference,
                                  List<GlossaryTerm> glossary) {
    public StrategyPlanRequest {
        sourceLanguage = defaultValue(sourceLanguage, "en");
        targetLanguage = defaultValue(targetLanguage, "zh");
        domain = defaultValue(domain, "通用");
        ttsEnabled = Boolean.TRUE.equals(ttsEnabled);
        providerPreference = defaultValue(providerPreference, "auto");
        glossary = glossary == null ? List.of() : List.copyOf(glossary);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record GlossaryTerm(String sourceTerm, String targetTerm, String domain, int priority, String note) {
        public GlossaryTerm {
            priority = priority;
        }
    }
}
