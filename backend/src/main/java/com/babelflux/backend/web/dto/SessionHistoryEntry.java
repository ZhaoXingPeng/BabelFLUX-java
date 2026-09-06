package com.babelflux.backend.web.dto;

import com.babelflux.backend.domain.Session;
import java.time.Instant;
import java.util.List;

public record SessionHistoryEntry(String sessionId, String reportId, String sessionName,
                                  String productMode, String inputMode, String sourceLabel,
                                  String domain, String modelProfile, String sourceLanguage,
                                  String targetLanguage, String status, Instant startedAt,
                                  Instant endedAt, long durationMs, int segmentCount,
                                  List<String> availableFormats) {
    public static SessionHistoryEntry from(Session session) {
        long duration = session.getEndedAt() == null ? 0 :
                session.getEndedAt().toEpochMilli() - session.getCreatedAt().toEpochMilli();
        return new SessionHistoryEntry(session.getId(), null, session.getSessionName(),
                session.getProductMode(), session.getInputMode(), session.getSourceLabel(),
                session.getDomain(), session.getModelProfile(), session.getSourceLanguage(),
                session.getTargetLanguage(), session.getStatus(), session.getCreatedAt(),
                session.getEndedAt(), duration, session.getSegments().size(),
                List.of("txt", "srt", "md", "json"));
    }
}
