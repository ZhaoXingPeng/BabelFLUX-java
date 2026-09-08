package com.babelflux.backend.service;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionReportService {
    private final FinalCorrectionService correction;

    /** Lightweight constructor retained for deterministic unit tests and local fallback use. */
    public SessionReportService() { this.correction = null; }

    @Autowired
    public SessionReportService(FinalCorrectionService correction) { this.correction = correction; }

    /**
     * Remote correction must not hold MySQL locks for the provider timeout window.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SessionReport generate(Session session) {
        List<Session.Segment> sourceSegments = session.getSegments();
        FinalCorrectionService.CorrectionResult result = correction == null
                ? FinalCorrectionService.CorrectionResult.skipped("当前 Java 报告服务未配置纠偏适配器，使用实时译文生成。")
                : correction.correct(session, sourceSegments);
        List<SessionReport.Segment> reportSegments = toReportSegments(sourceSegments, result.finalById());
        List<SessionReport.Revision> finalRevisions = revisions(sourceSegments, result);
        List<SessionReport.Revision> realtimeRevisions = session.getRevisions().stream()
                .map(revision -> new SessionReport.Revision(revision.segmentId(), revision.beforeText(),
                        revision.afterText(), revision.reason(), "实时"))
                .toList();
        long segmentDuration = sourceSegments.stream()
                .mapToLong(segment -> Math.max(segment.startMs(), segment.endMs())).max().orElse(0L);
        long elapsedDuration = session.getEndedAt() == null ? 0L
                : Math.max(0L, session.getEndedAt().toEpochMilli() - session.getCreatedAt().toEpochMilli());
        // Segment timestamps follow the media timeline; wall-clock time also includes
        // provider shutdown and post-session correction, so only use it for empty sessions.
        long durationMs = sourceSegments.isEmpty() || segmentDuration <= 0
                ? elapsedDuration : segmentDuration;
        String durationText = formatDuration(durationMs);
        int segmentCount = reportSegments.size();
        String fallbackSummary = segmentCount == 0
                ? "本场无有效转写内容。"
                : "本场共 " + segmentCount + " 句，已完成实时转写与翻译。";
        String qualityNotes = value(result.qualityNotes(), result.error());
        if (session.getDroppedInputFrames() > 0) {
            String inputLoss = "实时输入曾丢弃 " + session.getDroppedInputFrames() + " 帧（约 "
                    + session.getDroppedInputMs() + " ms），报告可能缺少部分原声；请检查网络或增大 REALTIME_QUEUE_FRAMES。";
            qualityNotes = qualityNotes.isBlank() ? inputLoss : qualityNotes + " " + inputLoss;
        }
        if (qualityNotes.isBlank()) qualityNotes = "completed".equals(result.status())
                ? "会后完整纠偏已完成。" : "报告使用实时译文生成。";
        String summary = value(result.summary(), fallbackSummary);
        return new SessionReport(
                session.getId() + "-report",
                session.getId(),
                session.getSessionName(),
                session.getProductMode(),
                session.getInputMode(),
                session.getSourceLabel(),
                session.getDomain(),
                session.getModelProfile(),
                session.getSourceLanguage(),
                session.getTargetLanguage(),
                durationMs,
                durationText,
                Instant.now(),
                summary,
                qualityNotes,
                result.glossaryHits(),
                new SessionReport.Metrics(segmentCount, realtimeRevisions.size(), finalRevisions.size(), durationText),
                reportSegments,
                finalRevisions,
                realtimeRevisions,
                result.model(),
                result.status(),
                result.error(),
                result.elapsedMs());
    }

    private List<SessionReport.Segment> toReportSegments(List<Session.Segment> segments,
                                                           Map<String, String> finalById) {
        return segments.stream().map(segment -> {
            String live = value(segment.translationText());
            String finalText = value(finalById.get(segment.segmentId()), live);
            if (finalText.isBlank()) finalText = live;
            return new SessionReport.Segment(
                    segment.segmentId(),
                    Math.max(0L, segment.startMs()),
                    Math.max(0L, segment.endMs()),
                    formatDuration(Math.max(0L, segment.startMs())),
                    value(segment.sourceText()),
                    live,
                    finalText,
                    "revised".equalsIgnoreCase(segment.status()));
        }).toList();
    }

    private List<SessionReport.Revision> revisions(List<Session.Segment> segments,
                                                   FinalCorrectionService.CorrectionResult result) {
        Map<String, SessionReport.Revision> byId = new LinkedHashMap<>();
        for (SessionReport.Revision revision : result.revisions()) byId.put(revision.segmentId(), revision);
        for (Session.Segment segment : segments) {
            String live = value(segment.translationText());
            String finalText = value(result.finalById().get(segment.segmentId()), live);
            if (!live.isBlank() && !finalText.isBlank() && !live.equals(finalText)
                    && !byId.containsKey(segment.segmentId())) {
                byId.put(segment.segmentId(), new SessionReport.Revision(segment.segmentId(), live, finalText,
                        "会后全局校正", "会后"));
            }
        }
        return List.copyOf(new ArrayList<>(byId.values()));
    }

    static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs) / 1000L;
        return "%02d:%02d".formatted(totalSeconds / 60L, totalSeconds % 60L);
    }

    private static String value(String value) { return value == null ? "" : value; }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
