package com.babelflux.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelflux.backend.provider.strategy.ModelStrategyService;
import com.babelflux.backend.provider.strategy.StrategyPlanRequest;
import org.junit.jupiter.api.Test;

class ModelStrategyServiceTest {
    private final ModelStrategyService service = new ModelStrategyService();

    @Test
    void buildsDeterministicRealtimePlanWithPriorityGlossary() {
        var request = new StrategyPlanRequest("en", "zh", "技术", true, "gummy", java.util.List.of(
                new StrategyPlanRequest.GlossaryTerm("API", "接口", null, 1, null),
                new StrategyPlanRequest.GlossaryTerm("API", "应用程序接口", null, 10, "preferred")));

        var plan = service.plan(request);

        assertEquals("gummy_realtime", plan.primaryProvider());
        assertEquals(java.util.List.of("qwen_live_translate", "fun_asr"), plan.fallbackProviders());
        assertEquals("qwen_tts", plan.ttsProvider());
        assertEquals(java.util.List.of("text", "audio"),
                plan.liveTranslateSession().get("event") instanceof java.util.Map<?, ?> event
                        && event.get("session") instanceof java.util.Map<?, ?> session
                        && session.get("modalities") instanceof java.util.List<?> modalities
                        ? modalities : java.util.List.of());
        assertTrue(plan.finalCorrectionPrompt().contains("API -> 应用程序接口"));
        assertEquals(4, plan.realtimeRevisionPolicy().windowSegments());
    }

    @Test
    void defaultsToLiveTranslateAndDisablesTts() {
        var plan = service.plan(new StrategyPlanRequest(null, null, null, false, null, null));

        assertEquals("qwen_live_translate", plan.primaryProvider());
        assertEquals(null, plan.ttsProvider());
        assertEquals("en", ((java.util.Map<?, ?>) ((java.util.Map<?, ?>) plan.liveTranslateSession()
                .get("event")).get("session")).get("input_audio_transcription") instanceof java.util.Map<?, ?> asr
                ? asr.get("language") : null);
    }
}
