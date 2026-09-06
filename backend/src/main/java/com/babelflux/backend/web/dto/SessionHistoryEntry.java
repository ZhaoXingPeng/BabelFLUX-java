package com.babelflux.backend.web.dto;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import java.time.Instant;
import java.util.List;

public record SessionHistoryEntry(String sessionId, String reportId, String sessionName,
                                  String productMode, String inputMode, String sourceLabel,
                                  String domain, String modelProfile, String sourceLanguage,
                                  String targetLanguage, String status, Instant startedAt,
                                  Instant endedAt, long durationMs, int segmentCount,
                                  int realtimeRevisionCount, int finalRevisionCount,
                                  String correctionStatus, Instant updatedAt,
                                  List<String> availableFormats) {
    public static SessionHistoryEntry from(Session session) {
        SessionReport report = session.getReport();
        long duration = session.getEndedAt() == null ? 0 :
                session.getEndedAt().toEpochMilli() - session.getCreatedAt().toEpochMilli();
        int segmentCount = report == null ? session.getSegments().size() : report.metrics().segments();
        return new SessionHistoryEntry(session.getId(), report == null ? null : report.reportId(), session.getSessionName(),
                session.getProductMode(), session.getInputMode(), session.getSourceLabel(),
                session.getDomain(), session.getModelProfile(), session.getSourceLanguage(),
                session.getTargetLanguage(), session.getStatus(), session.getCreatedAt(),
                session.getEndedAt(), report == null ? duration : report.durationMs(), segmentCount,
                report == null ? 0 : report.metrics().realtimeRevisions(),
                report == null ? 0 : report.metrics().finalRevisions(),
                report == null ? "pending" : report.correctionStatus(),
                report == null ? session.getCreatedAt() : report.generatedAt(),
                report == null ? List.of() : List.of("txt", "srt", "md", "json"));
    }
}
