package com.babelflux.backend.domain;

import java.time.Instant;
import java.util.List;

/** Immutable report snapshot produced when a session is finished. */
public record SessionReport(
        String reportId,
        String sessionId,
        String sessionName,
        String productMode,
        String inputMode,
        String sourceLabel,
        String domain,
        String modelProfile,
        String sourceLanguage,
        String targetLanguage,
        long durationMs,
        String durationText,
        Instant generatedAt,
        String summary,
        String qualityNotes,
        List<GlossaryHit> glossaryHits,
        Metrics metrics,
        List<Segment> segments,
        List<Revision> finalRevisions,
        List<Revision> realtimeRevisions,
        String correctionModel,
        String correctionStatus,
        String correctionError,
        long correctionElapsedMs) {

    public record Metrics(int segments, int realtimeRevisions, int finalRevisions, String durationText) {}

    public record Segment(String segmentId, long startMs, long endMs, String timecode,
                          String sourceText, String liveTranslation, String finalTranslation,
                          boolean revisedRealtime) {}

    public record Revision(String segmentId, String beforeText, String afterText,
                           String reason, String stage) {}

    public record GlossaryHit(String term, String translation) {}
}
