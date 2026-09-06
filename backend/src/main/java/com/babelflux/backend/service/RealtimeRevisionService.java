package com.babelflux.backend.service;

import com.babelflux.backend.config.DashScopeProperties;
import com.babelflux.backend.domain.Session;
import com.babelflux.backend.provider.dashscope.DashScopeClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Low-latency, conservative review of a bounded segment window. */
@Service
public class RealtimeRevisionService {
    private static final double MIN_CONFIDENCE = 0.62d;

    private final DashScopeClient client;
    private final DashScopeProperties properties;
    private final ObjectMapper mapper;

    public RealtimeRevisionService(DashScopeClient client, DashScopeProperties properties,
                                   ObjectMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    public List<Revision> review(Session session, List<Session.Segment> recent) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank() || recent.size() < 2) {
            return List.of();
        }
        int window = Math.max(2, properties.getRealtimeRevisionWindowSegments());
        List<Session.Segment> bounded = recent.subList(Math.max(0, recent.size() - window), recent.size());
        if (bounded.size() < 2) return List.of();
        String model = revisionModel(session.getModelProfile());
        String system = prompt(session.getDomain(), session.getSourceLanguage(), session.getTargetLanguage());
        String user = bounded.stream()
                .map(segment -> "[" + segment.segmentId() + "] 原文: " + value(segment.sourceText())
                        + "\n译文: " + value(segment.translationText()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        DashScopeClient.LlmGenerateResponse response = client.generate(model, "text",
                List.of(Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", "最近句子（最后一句不可修改）：\n" + user)),
                Map.of("result_format", "message", "temperature", 0.1));
        return parse(response.content(), bounded);
    }

    public String revisionModel(String profile) {
        return switch (profile == null ? "" : profile) {
            case "快速低延迟" -> value(properties.getFastRealtimeRevisionModel(), properties.getRealtimeRevisionModel());
            case "高准确" -> value(properties.getAccurateRealtimeRevisionModel(), properties.getRealtimeRevisionModel());
            case "成本优先" -> value(properties.getCostRealtimeRevisionModel(), properties.getRealtimeRevisionModel());
            default -> value(properties.getRealtimeRevisionModel(), "qwen-flash");
        };
    }

    private List<Revision> parse(String content, List<Session.Segment> window) {
        try {
            JsonNode root = mapper.readTree(jsonBody(content));
            Map<String, Session.Segment> byId = new LinkedHashMap<>();
            for (int i = 0; i < window.size() - 1; i++) {
                Session.Segment segment = window.get(i);
                if (segment.segmentId() != null && !segment.segmentId().isBlank()) byId.put(segment.segmentId(), segment);
            }
            List<Revision> result = new ArrayList<>();
            JsonNode items = root == null ? null : root.path("revisions");
            if (items == null || !items.isArray()) return List.of();
            for (JsonNode item : items) {
                String id = text(item, "segmentId", "id");
                Session.Segment segment = byId.get(id);
                String after = text(item, "afterText", "after");
                double confidence = item == null ? 0 : item.path("confidence").asDouble(0);
                if (segment == null || after == null || after.isBlank() || confidence < MIN_CONFIDENCE
                        || after.equals(segment.translationText())) continue;
                result.add(new Revision(id, value(segment.translationText()), after.trim(),
                        value(text(item, "reason"), "结合上下文修正"), confidence));
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String prompt(String domain, String source, String target) {
        String focus = switch (domain == null ? "" : domain) {
            case "技术" -> "保留 API、框架、模型、论文、产品名和缩写；术语表优先。";
            case "商务" -> "公司名、职位、货币、指标、数字和承诺必须精确。";
            case "医疗" -> "医学术语、剂量、数值和否定不得出错，不扩写诊断。";
            case "法律" -> "主体、义务、期限和否定必须准确，不补充法律结论。";
            default -> "忠实保留原意，中文自然，不做风格润色。";
        };
        return "你是 BabelFlux 实时纠偏模块。源语言：" + value(source, "auto") + "；目标语言："
                + value(target, "zh") + "；领域策略：" + focus + "\n"
                + "只修正之前句子中明确的术语、人名、数字、单位、否定或语义错误；不要修改最后一句。"
                + "仅输出 JSON：{\"revisions\":[{\"segmentId\":\"...\",\"afterText\":\"...\","
                + "\"reason\":\"...\",\"confidence\":0.0}]}，没有明确错误则返回空数组。";
    }

    private static String jsonBody(String content) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            int firstBrace = value.indexOf('{');
            int lastBrace = value.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) return value.substring(firstBrace, lastBrace + 1);
        }
        int firstBrace = value.indexOf('{');
        int lastBrace = value.lastIndexOf('}');
        return firstBrace >= 0 && lastBrace > firstBrace ? value.substring(firstBrace, lastBrace + 1) : value;
    }

    private static String text(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    private static String value(String value) { return value == null ? "" : value; }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    public record Revision(String segmentId, String beforeText, String afterText, String reason, double confidence) {}
}
