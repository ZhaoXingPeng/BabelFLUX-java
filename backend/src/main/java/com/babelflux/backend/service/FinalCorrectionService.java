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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
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
    private static final int COMPLETENESS_GUARD_MIN_LENGTH = 8;
    private static final int MAX_RECOVERY_SEGMENTS = 64;
    private static final double COMPLETENESS_GUARD_MIN_RATIO = 0.75;
    private static final double COMPLETENESS_GUARD_MIN_OVERLAP = 0.70;
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
        if (segments.size() > properties.getFinalCorrectionBatchSize()) {
            return correctBatched(session, segments, model);
        }
        return correctSingle(session, segments, model);
    }

    private CorrectionResult correctSingle(Session session, List<Session.Segment> segments, String model) {
        long started = System.nanoTime();
        long deadline = started + timeout().toNanos();
        Future<DashScopeClient.LlmGenerateResponse> task = executor.submit(() -> client.generate(
                model, "text", messages(session, segments), Map.of(
                        "result_format", "message", "temperature", 0.2)));
        try {
            long remaining = remainingMillis(deadline);
            if (remaining <= 0) throw new TimeoutException();
            DashScopeClient.LlmGenerateResponse response = task.get(remaining, TimeUnit.MILLISECONDS);
            if (response == null) {
                return CorrectionResult.fallback(model, "会后完整纠偏返回空响应，报告使用实时译文生成。",
                        elapsed(started));
            }
            Parsed parsed = parse(response.content(), segments);
            if (parsed == null) {
                return CorrectionResult.fallback(model, "会后完整纠偏未返回可解析 JSON，报告使用实时译文生成。",
                        elapsed(started));
            }
            RecoveryOutcome recovery = recoverMissing(session, missingSegments(segments, parsed.finalById), model,
                    deadline);
            Parsed merged = merge(parsed, recovery.parsed());
            return result(model, segments, merged, recovery, started);
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

    private CorrectionResult correctBatched(Session session, List<Session.Segment> segments, String model) {
        long started = System.nanoTime();
        List<List<Session.Segment>> batches = partition(segments, properties.getFinalCorrectionBatchSize());
        CompletionService<BatchResponse> completion = new ExecutorCompletionService<>(executor);
        List<Future<BatchResponse>> tasks = new ArrayList<>(batches.size());
        for (int index = 0; index < batches.size(); index++) {
            final int batchIndex = index;
            tasks.add(completion.submit(() -> new BatchResponse(batchIndex, client.generate(
                    model, "text", messages(session, batches.get(batchIndex)), Map.of(
                            "result_format", "message", "temperature", 0.2)))));
        }

        Map<String, String> finalById = new LinkedHashMap<>();
        List<SessionReport.Revision> revisions = new ArrayList<>();
        List<SessionReport.GlossaryHit> glossaryHits = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        List<String> qualityNotes = new ArrayList<>();
        int rejected = 0;
        int failed = 0;
        int timedOut = 0;
        long deadline = started + timeout().toNanos();
        int completed = 0;
        RecoveryOutcome recovery = RecoveryOutcome.none();
        try {
            // Consume whichever batch finishes first; a slow provider must not hide completed windows.
            while (completed < tasks.size()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    timedOut += tasks.size() - completed;
                    break;
                }
                Future<BatchResponse> completedTask;
                try {
                    completedTask = completion.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    timedOut += tasks.size() - completed;
                    break;
                }
                if (completedTask == null) {
                    timedOut += tasks.size() - completed;
                    break;
                }
                completed++;
                BatchResponse batchResponse;
                try {
                    batchResponse = completedTask.get();
                } catch (ExecutionException | java.util.concurrent.CancellationException error) {
                    failed++;
                    continue;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    timedOut += tasks.size() - completed;
                    break;
                }
                if (batchResponse == null || batchResponse.response() == null) {
                    failed++;
                    continue;
                }
                Parsed parsed = parse(batchResponse.response().content(), batches.get(batchResponse.index()));
                if (parsed == null) {
                    failed++;
                    continue;
                }
                finalById.putAll(parsed.finalById());
                revisions.addAll(parsed.revisions());
                glossaryHits.addAll(parsed.glossaryHits());
                if (!value(parsed.summary()).isBlank()) summaries.add(parsed.summary());
                if (!value(parsed.qualityNotes()).isBlank()) qualityNotes.add(parsed.qualityNotes());
                rejected += parsed.rejectedForCompleteness();
            }
            recovery = recoverMissing(session, missingSegments(segments, finalById), model, deadline);
            Parsed merged = merge(new Parsed(finalById, revisions, glossaryHits,
                    String.join(" ", summaries), String.join(" ", qualityNotes), rejected), recovery.parsed());
            finalById = new LinkedHashMap<>(merged.finalById());
            revisions = new ArrayList<>(merged.revisions());
            glossaryHits = new ArrayList<>(merged.glossaryHits());
            summaries = new ArrayList<>();
            if (!value(merged.summary()).isBlank()) summaries.add(merged.summary());
            qualityNotes = new ArrayList<>();
            if (!value(merged.qualityNotes()).isBlank()) qualityNotes.add(merged.qualityNotes());
            rejected = merged.rejectedForCompleteness();
        } finally {
            tasks.stream().filter(task -> !task.isDone()).forEach(task -> task.cancel(true));
        }

        int missing = segments.size() - finalById.size();
        String status = missing == 0 ? "completed"
                : rejected > 0 || !finalById.isEmpty() ? "partial"
                : timedOut > 0 ? "timeout" : "fallback";
        List<String> errors = new ArrayList<>();
        if (missing > 0) errors.add("会后完整纠偏返回缺少 " + missing + " 句，缺失句已使用实时译文。");
        if (rejected > 0) errors.add("完整性保护拒绝 " + rejected + " 句显著缩短的会后译文，保留实时译文。");
        if (timedOut > 0) errors.add("会后完整纠偏有 " + timedOut + " 个批次超时，报告使用实时译文兜底。");
        if (failed > 0) errors.add("会后完整纠偏有 " + failed + " 个批次调用失败，报告使用实时译文兜底。");
        if (recovery.error() != null && !recovery.error().isBlank()) errors.add(recovery.error());
        String error = String.join(" ", errors);
        String summary = String.join(" ", summaries);
        String quality = String.join(" ", qualityNotes);
        if (recovery.attempted()) {
            quality = appendQualityNotes(quality, "会后缺段补救调用 1 次，耗时 " + recovery.elapsedMs() + " ms。");
        }
        return new CorrectionResult(status, model, finalById, List.copyOf(revisions),
                List.copyOf(glossaryHits), summary, appendQualityNotes(quality, error), error, elapsed(started));
    }

    private CorrectionResult result(String model, List<Session.Segment> segments, Parsed parsed,
                                    RecoveryOutcome recovery, long started) {
        int missing = segments.size() - parsed.finalById.size();
        String status = missing == 0 ? "completed"
                : parsed.rejectedForCompleteness() > 0 ? "partial"
                : parsed.finalById.isEmpty() ? "fallback" : "partial";
        List<String> errors = new ArrayList<>();
        if (missing > 0) errors.add("会后完整纠偏返回缺少 " + missing + " 句，缺失句已使用实时译文。");
        if (parsed.rejectedForCompleteness() > 0) {
            errors.add("完整性保护拒绝 " + parsed.rejectedForCompleteness() + " 句显著缩短的会后译文，保留实时译文。");
        }
        if (recovery.error() != null && !recovery.error().isBlank()) errors.add(recovery.error());
        String error = String.join(" ", errors);
        String recoveryNote = recovery.attempted()
                ? "会后缺段补救调用 1 次，耗时 " + recovery.elapsedMs() + " ms。" : "";
        String quality = appendQualityNotes(parsed.qualityNotes(), recoveryNote);
        quality = appendQualityNotes(quality, error);
        return new CorrectionResult(status, model, parsed.finalById(), parsed.revisions(), parsed.glossaryHits(),
                value(parsed.summary()), quality, error, elapsed(started));
    }

    private RecoveryOutcome recoverMissing(Session session, List<Session.Segment> missing, String model,
                                           long deadline) {
        if (missing.isEmpty()) return RecoveryOutcome.none();
        final List<Session.Segment> recoverySegments = missing.size() > MAX_RECOVERY_SEGMENTS
                ? List.copyOf(missing.subList(0, MAX_RECOVERY_SEGMENTS)) : missing;
        long remaining = remainingMillis(deadline);
        if (remaining <= 0) return RecoveryOutcome.failure("缺段补救未执行：已达到会后纠偏 deadline。", false, 0);
        long started = System.nanoTime();
        Future<DashScopeClient.LlmGenerateResponse> task = executor.submit(() -> client.generate(
                model, "text", recoveryMessages(session, recoverySegments), Map.of(
                        "result_format", "message", "temperature", 0.1)));
        try {
            DashScopeClient.LlmGenerateResponse response = task.get(remaining, TimeUnit.MILLISECONDS);
            if (response == null) {
                return RecoveryOutcome.failure("缺段补救返回空响应，缺失句继续使用实时译文。", true, elapsed(started));
            }
            Parsed parsed = parse(response.content(), recoverySegments);
            if (parsed == null) {
                return RecoveryOutcome.failure("缺段补救未返回可解析 JSON，缺失句继续使用实时译文。", true,
                        elapsed(started));
            }
            int unresolved = recoverySegments.size() - parsed.finalById().size();
            String error = unresolved == 0 ? "" : "缺段补救仍缺少 " + unresolved + " 句，缺失句继续使用实时译文。";
            return new RecoveryOutcome(parsed, error, true, elapsed(started));
        } catch (TimeoutException error) {
            task.cancel(true);
            return RecoveryOutcome.failure("缺段补救超时，缺失句继续使用实时译文。", true, elapsed(started));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return RecoveryOutcome.failure("缺段补救被中断，缺失句继续使用实时译文。", true, elapsed(started));
        } catch (ExecutionException | java.util.concurrent.CancellationException error) {
            Throwable cause = error instanceof ExecutionException execution && execution.getCause() != null
                    ? execution.getCause() : error;
            return RecoveryOutcome.failure("缺段补救调用失败（" + cause.getClass().getSimpleName()
                    + "），缺失句继续使用实时译文。", true, elapsed(started));
        }
    }

    private List<Map<String, Object>> recoveryMessages(Session session, List<Session.Segment> segments) {
        String strict = prompt(session) + "\n这是缺段补救请求。只处理下面列出的缺失句，禁止输出任何其他 id；必须逐句输出，不能省略。";
        StringBuilder user = new StringBuilder("缺失句如下：\n");
        for (Session.Segment segment : segments) {
            user.append('[').append(segment.segmentId()).append("] 原文: ")
                    .append(value(segment.sourceText())).append("\n实时译文: ")
                    .append(value(segment.translationText())).append('\n');
        }
        return List.of(Map.of("role", "system", "content", strict), Map.of("role", "user", "content", user.toString()));
    }

    private static List<Session.Segment> missingSegments(List<Session.Segment> segments, Map<String, String> finalById) {
        return segments.stream().filter(segment -> !finalById.containsKey(segment.segmentId())).toList();
    }

    private static Parsed merge(Parsed first, Parsed second) {
        if (second == null) return first;
        Map<String, String> finals = new LinkedHashMap<>(first.finalById());
        second.finalById().forEach(finals::putIfAbsent);
        List<SessionReport.Revision> revisions = new ArrayList<>(first.revisions());
        revisions.addAll(second.revisions());
        List<SessionReport.GlossaryHit> glossaryHits = new ArrayList<>(first.glossaryHits());
        glossaryHits.addAll(second.glossaryHits());
        String summary = join(first.summary(), second.summary());
        String qualityNotes = join(first.qualityNotes(), second.qualityNotes());
        return new Parsed(finals, List.copyOf(revisions), List.copyOf(glossaryHits), summary, qualityNotes,
                first.rejectedForCompleteness() + second.rejectedForCompleteness());
    }

    private static String join(String first, String second) {
        String left = value(first);
        String right = value(second);
        return left.isBlank() ? right : right.isBlank() ? left : left + " " + right;
    }

    private static long remainingMillis(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) return 0;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
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
        StringBuilder user = new StringBuilder("待纠偏句子如下：\n");
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
                + "通读提供的原文和实时译文，为每句输出最终译文；仅修正明确的误译、术语、人名、数字、单位、否定或语义错误，不能扩写。"
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
            int rejectedForCompleteness = 0;
            JsonNode outputSegments = root.path("segments");
            if (outputSegments.isArray()) {
                for (JsonNode item : outputSegments) {
                    String id = text(item, "id", "segmentId");
                    String translation = text(item, "finalTranslation", "translation");
                    Session.Segment source = byId.get(id);
                    if (source != null && translation != null && !translation.isBlank()) {
                        String candidate = translation.trim();
                        if (passesCompletenessGuard(source.sourceText(), source.translationText(), candidate)) finals.put(id, candidate);
                        else rejectedForCompleteness++;
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
                    if (segment == null || after == null || after.isBlank()
                            || !after.trim().equals(finals.get(id))) continue;
                    String before = value(segment.translationText());
                    if (before.equals(after.trim())) continue;
                    revisions.add(new SessionReport.Revision(id, before, after.trim(),
                            value(text(item, "reason"), "会后全局校正"), "会后"));
                }
            }
            return new Parsed(finals, revisions, glossaryHits(root.path("glossaryHits")),
                    text(root, "summary"), text(root, "qualityNotes"), rejectedForCompleteness);
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
    private static List<List<Session.Segment>> partition(List<Session.Segment> segments, int batchSize) {
        List<List<Session.Segment>> batches = new ArrayList<>();
        for (int from = 0; from < segments.size(); from += batchSize) {
            batches.add(List.copyOf(segments.subList(from, Math.min(segments.size(), from + batchSize))));
        }
        return List.copyOf(batches);
    }
    private static boolean passesCompletenessGuard(String source, String live, String candidate) {
        String normalizedLive = compact(live);
        String normalizedCandidate = compact(candidate);
        if (normalizedLive.length() < COMPLETENESS_GUARD_MIN_LENGTH) return true;
        if (normalizedCandidate.length() < Math.ceil(normalizedLive.length() * COMPLETENESS_GUARD_MIN_RATIO)) return false;
        if (longestCommonSubsequence(normalizedLive, normalizedCandidate)
                < Math.ceil(normalizedLive.length() * COMPLETENESS_GUARD_MIN_OVERLAP)) return false;
        // Token checks use the original spacing; compacting first would merge
        // adjacent words such as "Babel Flux" into one unrelated token.
        return preservesLongTokens(source, live, candidate);
    }
    private static String compact(String value) { return value == null ? "" : value.replaceAll("\\s+", ""); }
    private static int longestCommonSubsequence(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int i = 1; i <= left.length(); i++) {
            int diagonal = 0;
            for (int j = 1; j <= right.length(); j++) {
                int saved = previous[j];
                previous[j] = left.charAt(i - 1) == right.charAt(j - 1)
                        ? diagonal + 1 : Math.max(previous[j], previous[j - 1]);
                diagonal = saved;
            }
        }
        return previous[right.length()];
    }
    private static boolean preservesLongTokens(String source, String live, String candidate) {
        java.util.Set<String> sourceTokens = new java.util.HashSet<>();
        java.util.regex.Matcher sourceMatcher = java.util.regex.Pattern.compile("[A-Za-z0-9]{2,}")
                .matcher(source == null ? "" : source);
        while (sourceMatcher.find()) sourceTokens.add(sourceMatcher.group().toLowerCase(java.util.Locale.ROOT));
        java.util.Set<String> candidateTokens = new java.util.HashSet<>();
        java.util.regex.Matcher candidateMatcher = java.util.regex.Pattern.compile("[A-Za-z0-9]{2,}")
                .matcher(candidate);
        while (candidateMatcher.find()) candidateTokens.add(candidateMatcher.group().toLowerCase(java.util.Locale.ROOT));
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[A-Za-z0-9]{2,}").matcher(live);
        String normalizedCandidate = candidate.toLowerCase(java.util.Locale.ROOT);
        while (matcher.find()) {
            String token = matcher.group();
            if (candidate.contains(token)) continue;
            boolean sourceBackedCorrection = sourceTokens.stream()
                    .filter(sourceToken -> candidateTokens.contains(sourceToken)
                            || sourceToken.length() >= 4 && normalizedCandidate.contains(sourceToken))
                    .anyMatch(sourceToken -> editDistanceAtMost(token.toLowerCase(java.util.Locale.ROOT), sourceToken, 2));
            if (!sourceBackedCorrection) return false;
        }
        return true;
    }
    private static boolean editDistanceAtMost(String left, String right, int limit) {
        if (Math.abs(left.length() - right.length()) > limit) return false;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                current[j] = left.charAt(i - 1) == right.charAt(j - 1) ? previous[j - 1]
                        : 1 + Math.min(previous[j - 1], Math.min(previous[j], current[j - 1]));
            }
            previous = current;
        }
        return previous[right.length()] <= limit;
    }
    private static String appendQualityNotes(String qualityNotes, String safety) {
        if (safety == null || safety.isBlank()) return value(qualityNotes);
        return qualityNotes == null || qualityNotes.isBlank() ? safety : qualityNotes + " " + safety;
    }
    private static String value(String value) { return value == null ? "" : value; }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    private record Parsed(Map<String, String> finalById, List<SessionReport.Revision> revisions,
                          List<SessionReport.GlossaryHit> glossaryHits, String summary, String qualityNotes,
                          int rejectedForCompleteness) {}

    private record BatchResponse(int index, DashScopeClient.LlmGenerateResponse response) {}

    private record RecoveryOutcome(Parsed parsed, String error, boolean attempted, long elapsedMs) {
        static RecoveryOutcome none() { return new RecoveryOutcome(null, "", false, 0); }
        static RecoveryOutcome failure(String error, boolean attempted, long elapsedMs) {
            return new RecoveryOutcome(null, error, attempted, elapsedMs);
        }
    }

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
