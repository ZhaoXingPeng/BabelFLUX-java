package com.babelflux.backend.service;

import com.babelflux.backend.config.DashScopeProperties;
import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.babelflux.backend.provider.dashscope.DashScopeClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/** Generates a complete report correction while preserving a deterministic fallback. */
@Service
public class FinalCorrectionService {
    private final DashScopeClient client;
    private final DashScopeProperties properties;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public FinalCorrectionService(DashScopeClient client, DashScopeProperties properties, ObjectMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    public CorrectionResult correct(Session session, List<Session.Segment> segments) {
        if (segments.isEmpty()) {
            return CorrectionResult.skipped("本场无有效转写与译文，未执行会后完整纠偏。");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return CorrectionResult.skipped("未配置百炼 API key，报告使用实时译文生成。");
        }
        String model = correctionModel(session.getModelProfile());
        Future<DashScopeClient.LlmGenerateResponse> task = executor.submit(() -> client.generate(
                model, "text", messages(session, segments), Map.of(
                        "result_format", "message", "temperature", 0.2)));
        long started = System.nanoTime();
        try {
            DashScopeClient.LlmGenerateResponse response = task.get(timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response == null) {
                return CorrectionResult.fallback(model, "会后完整纠偏返回空响应，报告使用实时译文生成。",
                        elapsed(started));
            }
            Parsed parsed = parse(response.content(), segments);
            if (parsed == null) {
                return CorrectionResult.fallback(model, "会后完整纠偏未返回可解析 JSON，报告使用实时译文生成。",
                        elapsed(started));
            }
            int missing = segments.size() - parsed.finalById.size();
            String status = missing == 0 ? "completed" : parsed.finalById.isEmpty() ? "fallback" : "partial";
            String error = missing == 0 ? "" : "会后完整纠偏返回缺少 " + missing + " 句，缺失句已使用实时译文。";
            return new CorrectionResult(status, model, parsed.finalById, parsed.revisions, parsed.glossaryHits,
                    value(parsed.summary()), value(parsed.qualityNotes(), error), error, elapsed(started));
        } catch (TimeoutException error) {
            task.cancel(true);
            return new CorrectionResult("timeout", model, Map.of(), List.of(), List.of(), "",
                    "会后完整纠偏超时，报告使用实时译文生成。", "会后完整纠偏超时：超过 "
                            + timeout().toSeconds() + " 秒未完成。", elapsed(started));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return CorrectionResult.fallback(model, "会后完整纠偏被中断，报告使用实时译文生成。", elapsed(started));
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return CorrectionResult.fallback(model, "会后完整纠偏调用失败，报告使用实时译文生成。原因："
                    + cause.getClass().getSimpleName(), elapsed(started));
        }
    }

    public String correctionModel(String profile) {
        return switch (profile == null ? "" : profile) {
            case "快速低延迟" -> value(properties.getFastFinalCorrectionModel(), properties.getFinalCorrectionModel());
            case "高准确" -> value(properties.getAccurateFinalCorrectionModel(), properties.getFinalCorrectionModel());
            case "成本优先" -> value(properties.getCostFinalCorrectionModel(), properties.getFinalCorrectionModel());
            default -> value(properties.getFinalCorrectionModel(), "qwen-plus");
        };
    }

    @PreDestroy
    void shutdown() { executor.shutdownNow(); }

    private Duration timeout() {
        Duration configured = properties.getFinalCorrectionTimeout();
        return configured == null || configured.isNegative() || configured.isZero()
                ? Duration.ofSeconds(30) : configured;
    }

    private List<Map<String, Object>> messages(Session session, List<Session.Segment> segments) {
        StringBuilder user = new StringBuilder("整场句子如下：\n");
        for (Session.Segment segment : segments) {
            user.append('[').append(segment.segmentId()).append("] ("
                    ).append(formatTime(segment.startMs())).append(") 原文: ")
                    .append(value(segment.sourceText())).append("\n实时译文: ")
                    .append(value(segment.translationText())).append('\n');
        }
        return List.of(Map.of("role", "system", "content", prompt(session)),
                Map.of("role", "user", "content", user.toString()));
    }

    private static String prompt(Session session) {
        String focus = switch (session.getDomain() == null ? "" : session.getDomain()) {
            case "技术" -> "保留 API、框架、模型、论文、产品名和缩写；术语表优先。";
            case "商务" -> "公司名、职位、货币、指标、数字和承诺必须精确。";
            case "教育" -> "概念解释准确，术语前后一致，避免过度口语化。";
            case "医疗" -> "医学术语、剂量、数值和否定不得出错，不扩写诊断。";
            case "法律" -> "主体、义务、期限和否定必须准确，不补充法律结论。";
            default -> "忠实保留原意，中文自然，不做风格润色。";
        };
        return "你是 BabelFlux 会后完整纠偏模块。领域：" + value(session.getDomain(), "通用")
                + "；源语言：" + value(session.getSourceLanguage(), "auto") + "；目标语言："
                + value(session.getTargetLanguage(), "zh") + "。领域策略：" + focus + "\n"
                + "通读整场原文和实时译文，为每句输出最终译文；仅修正明确的误译、术语、人名、数字、单位、否定或语义错误，不能扩写。"
                + "仅输出 JSON：{\"summary\":\"...\",\"qualityNotes\":\"...\","
                + "\"glossaryHits\":[{\"term\":\"...\",\"translation\":\"...\"}],"
                + "\"segments\":[{\"id\":\"...\",\"finalTranslation\":\"...\"}],"
                + "\"revisions\":[{\"id\":\"...\",\"before\":\"...\",\"after\":\"...\",\"reason\":\"...\"}]}。"
                + "术语表：" + glossary(session.getGlossary());
    }

    private Parsed parse(String content, List<Session.Segment> segments) {
        try {
            JsonNode root = mapper.readTree(jsonBody(content));
            if (root == null || !root.isObject()) return null;
            Map<String, Session.Segment> byId = new LinkedHashMap<>();
            for (Session.Segment segment : segments) byId.put(segment.segmentId(), segment);
            Map<String, String> finals = new LinkedHashMap<>();
            JsonNode outputSegments = root.path("segments");
            if (outputSegments.isArray()) {
                for (JsonNode item : outputSegments) {
                    String id = text(item, "id", "segmentId");
                    String translation = text(item, "finalTranslation", "translation");
                    if (id != null && byId.containsKey(id) && translation != null && !translation.isBlank()) {
                        finals.put(id, translation.trim());
                    }
                }
            }
            List<SessionReport.Revision> revisions = new ArrayList<>();
            JsonNode outputRevisions = root.path("revisions");
            if (outputRevisions.isArray()) {
                for (JsonNode item : outputRevisions) {
                    String id = text(item, "id", "segmentId");
                    Session.Segment segment = byId.get(id);
                    String after = text(item, "after", "afterText");
                    if (segment == null || after == null || after.isBlank()) continue;
                    String before = value(segment.translationText());
                    if (before.equals(after.trim())) continue;
                    revisions.add(new SessionReport.Revision(id, before, after.trim(),
                            value(text(item, "reason"), "会后全局校正"), "会后"));
                }
            }
            return new Parsed(finals, revisions, glossaryHits(root.path("glossaryHits")),
                    text(root, "summary"), text(root, "qualityNotes"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<SessionReport.GlossaryHit> glossaryHits(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<SessionReport.GlossaryHit> hits = new ArrayList<>();
        for (JsonNode item : node) {
            String term = text(item, "term", "source");
            String translation = text(item, "translation", "target");
            if (term != null && translation != null) hits.add(new SessionReport.GlossaryHit(term, translation));
        }
        return List.copyOf(hits);
    }

    private static String glossary(List<Session.GlossaryTerm> terms) {
        return terms.stream().sorted((left, right) -> Integer.compare(right.priority(), left.priority()))
                .filter(term -> term.sourceTerm() != null && !term.sourceTerm().isBlank()
                        && term.targetTerm() != null && !term.targetTerm().isBlank())
                .map(term -> "[" + term.sourceTerm() + " -> " + term.targetTerm() + "]")
                .reduce((left, right) -> left + ", " + right).orElse("（无）");
    }

    private static String jsonBody(String content) {
        String value = content == null ? "" : content.trim();
        int first = value.indexOf('{');
        int last = value.lastIndexOf('}');
        return first >= 0 && last > first ? value.substring(first, last + 1) : value;
    }

    private static String text(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return "%02d:%02d".formatted(seconds / 60L, seconds % 60L);
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static String value(String value) { return value == null ? "" : value; }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    private record Parsed(Map<String, String> finalById, List<SessionReport.Revision> revisions,
                          List<SessionReport.GlossaryHit> glossaryHits, String summary, String qualityNotes) {}

    public record CorrectionResult(String status, String model, Map<String, String> finalById,
                                   List<SessionReport.Revision> revisions, List<SessionReport.GlossaryHit> glossaryHits,
                                   String summary, String qualityNotes, String error, long elapsedMs) {
        static CorrectionResult skipped(String error) {
            return new CorrectionResult("skipped", null, Map.of(), List.of(), List.of(), "", error, error, 0);
        }
        static CorrectionResult fallback(String model, String error, long elapsedMs) {
            return new CorrectionResult("fallback", model, Map.of(), List.of(), List.of(), "", error, error, elapsedMs);
        }
    }
}
