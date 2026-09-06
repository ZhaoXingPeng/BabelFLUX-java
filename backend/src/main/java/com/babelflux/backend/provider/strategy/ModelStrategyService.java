package com.babelflux.backend.provider.strategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ModelStrategyService {
    private static final String LIVE_TRANSLATE = "qwen_live_translate";
    private static final String GUMMY = "gummy_realtime";
    private static final String FUN_ASR = "fun_asr";
    private static final String QWEN_TTS = "qwen_tts";

    public StrategyPlanResponse plan(StrategyPlanRequest request) {
        String primary = selectPrimary(request.providerPreference());
        List<String> fallback = List.of(LIVE_TRANSLATE, GUMMY, FUN_ASR).stream()
                .filter(provider -> !provider.equals(primary)).toList();
        StrategyPlanResponse.RealtimeRevisionPolicy policy = new StrategyPlanResponse.RealtimeRevisionPolicy(
                4, 40_000,
                List.of("partial_to_final_changed", "glossary_term_missing",
                        "new_context_changes_previous_meaning", "high_risk_number_name_negation",
                        "source_sync_recovered", "user_glossary_updated"),
                List.of("Only call LLM for high-risk segments inside the revision window.",
                        "Prefer deterministic glossary and final-result replacements before LLM.",
                        "Do not revise more than the configured per-minute limit."), 6);
        return new StrategyPlanResponse(
                "注意：本策略计划固定选择已接入的百炼 LiveTranslate + qwen 纠偏链路；"
                        + "gummy/fun_asr provider 仍仅作为后续回退方案，不会被静默切换。",
                primary, fallback, FUN_ASR, request.ttsEnabled() ? QWEN_TTS : null,
                liveTranslateSession(request), gummyConfig(request), policy,
                finalCorrectionPrompt(request));
    }

    private static String selectPrimary(String preference) {
        return switch (preference) {
            case "gummy" -> GUMMY;
            case "fun_asr" -> FUN_ASR;
            default -> LIVE_TRANSLATE;
        };
    }

    private static Map<String, Object> liveTranslateSession(StrategyPlanRequest request) {
        Map<String, Object> translation = new LinkedHashMap<>();
        translation.put("language", request.targetLanguage());
        Map<String, String> phrases = glossaryPhrases(request.glossary());
        if (!phrases.isEmpty()) translation.put("corpus", Map.of("phrases", phrases));

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("modalities", request.ttsEnabled() ? List.of("text", "audio") : List.of("text"));
        session.put("input_audio_format", "pcm");
        session.put("output_audio_format", "pcm");
        session.put("input_audio_transcription", Map.of("model", "qwen3-asr-flash-realtime",
                "language", request.sourceLanguage()));
        session.put("translation", translation);
        if (request.ttsEnabled()) session.put("voice", "Tina");
        return Map.of("model", "qwen3.5-livetranslate-flash-realtime",
                "event", Map.of("type", "session.update", "session", session));
    }

    private static Map<String, Object> gummyConfig(StrategyPlanRequest request) {
        return Map.of("model", "gummy-realtime-v1", "format", "pcm", "sample_rate", 16_000,
                "source_language", request.sourceLanguage(), "transcription_enabled", true,
                "translation_enabled", true, "translation_target_languages", List.of(request.targetLanguage()));
    }

    private static String finalCorrectionPrompt(StrategyPlanRequest request) {
        String guidance = switch (request.domain()) {
            case "技术" -> "保留 API、框架、模型、论文、产品名和缩写；术语表优先，不把专有名词误译为普通词。";
            case "商务" -> "保留公司名、职位、货币、指标和会议语气，数字与承诺类表述必须谨慎。";
            case "教育" -> "概念解释准确，表达清晰，避免过度口语化。";
            case "医疗" -> "医学术语谨慎，不扩写诊断或治疗建议。";
            case "法律" -> "法律术语准确，不自行解释法条或补充法律结论。";
            default -> "忠实保留原意，中文表达自然，优先保证字幕短句可读。";
        };
        String glossary = request.glossary().stream()
                .sorted(Comparator.comparingInt(StrategyPlanRequest.GlossaryTerm::priority).reversed())
                .filter(term -> term.sourceTerm() != null && !term.sourceTerm().isBlank()
                        && term.targetTerm() != null && !term.targetTerm().isBlank())
                .map(term -> "- " + term.sourceTerm() + " -> " + term.targetTerm()
                        + "；优先级：" + term.priority()
                        + (term.note() == null || term.note().isBlank() ? "" : "；备注：" + term.note()))
                .reduce((left, right) -> left + "\n" + right).orElse("无");
        return String.join("\n", List.of(
                "你是 BabelFlux / 巴别流 同传的最终全文纠偏模块。",
                "领域：" + request.domain(), "源语言：" + request.sourceLanguage(),
                "目标语言：" + request.targetLanguage(), "领域策略：" + guidance,
                "任务：基于完整源文、实时中文字幕、实时修正记录和术语表，生成最终双语稿和修正记录。",
                "硬性规则：不得扩写原意；不得删除时间轴；术语表优先；只修正影响理解、术语一致性或明显错误的内容。",
                "输出必须是 JSON，字段包括 finalTranscript、finalTranslation、finalRevisions、glossaryHits、summary、qualityNotes。",
                "术语表：", glossary));
    }

    private static Map<String, String> glossaryPhrases(List<StrategyPlanRequest.GlossaryTerm> glossary) {
        Map<String, String> result = new LinkedHashMap<>();
        glossary.stream().sorted(Comparator.comparingInt(StrategyPlanRequest.GlossaryTerm::priority).reversed())
                .filter(term -> term.sourceTerm() != null && !term.sourceTerm().isBlank()
                        && term.targetTerm() != null && !term.targetTerm().isBlank())
                .forEach(term -> result.putIfAbsent(term.sourceTerm(), term.targetTerm()));
        return result;
    }
}
