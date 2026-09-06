package com.babelflux.backend.service;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SessionReportService {
    public SessionReport generate(Session session) {
        List<SessionReport.Segment> reportSegments = session.getSegments().stream()
                .map(this::toReportSegment).toList();
        long segmentDuration = session.getSegments().stream()
                .mapToLong(segment -> Math.max(segment.startMs(), segment.endMs())).max().orElse(0L);
        long elapsedDuration = session.getEndedAt() == null ? 0L
                : Math.max(0L, session.getEndedAt().toEpochMilli() - session.getCreatedAt().toEpochMilli());
        long durationMs = Math.max(segmentDuration, elapsedDuration);
        String durationText = formatDuration(durationMs);
        int segmentCount = reportSegments.size();
        String summary = segmentCount == 0
                ? "本场无有效转写内容。"
                : "本场共 " + segmentCount + " 句，已完成实时转写与翻译。";
        String qualityNotes = "当前 Java 迁移切片未执行会后完整纠偏，报告使用实时译文生成。";
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
                List.of(),
                new SessionReport.Metrics(segmentCount, 0, 0, durationText),
                reportSegments,
                List.of(),
                List.of(),
                null,
                "skipped",
                qualityNotes,
                0L);
    }

    private SessionReport.Segment toReportSegment(Session.Segment segment) {
        String live = value(segment.translationText());
        return new SessionReport.Segment(
                segment.segmentId(),
                Math.max(0L, segment.startMs()),
                Math.max(0L, segment.endMs()),
                formatDuration(Math.max(0L, segment.startMs())),
                value(segment.sourceText()),
                live,
                live,
                "revised".equalsIgnoreCase(segment.status()));
    }

    static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs) / 1000L;
        return "%02d:%02d".formatted(totalSeconds / 60L, totalSeconds % 60L);
    }

    private static String value(String value) { return value == null ? "" : value; }
}
