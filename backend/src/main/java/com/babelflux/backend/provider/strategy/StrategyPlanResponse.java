package com.babelflux.backend.provider.strategy;

import java.util.List;
import java.util.Map;

public record StrategyPlanResponse(String disclaimer, String primaryProvider,
                                   List<String> fallbackProviders, String asrOnlyProvider,
                                   String ttsProvider, Map<String, Object> liveTranslateSession,
                                   Map<String, Object> gummyConfig,
                                   RealtimeRevisionPolicy realtimeRevisionPolicy,
                                   String finalCorrectionPrompt) {
    public record RealtimeRevisionPolicy(int windowSegments, int windowMs, List<String> triggers,
                                         List<String> llmEscalationRules, int maxRevisionsPerMinute) {}
}
