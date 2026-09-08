package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.babelflux.backend.domain.Session;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionReportServiceTest {
    private final SessionReportService reports = new SessionReportService();

    @Test
    void usesMediaTimelineWhenFinalizationWallClockIsLonger() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Session session = Session.restore("timeline", created, created.plusSeconds(60), "ended", "timeline",
                "en", "zh", "通用", "默认", "quick", "microphone", "audio",
                List.of(new Session.Segment("seg-1", "hello", "你好", 800, 3004, "final")), null);

        assertEquals(3004, reports.generate(session).durationMs());
    }

    @Test
    void fallsBackToWallClockForEmptySession() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Session session = Session.restore("empty", created, created.plusSeconds(60), "ended", "empty",
                "en", "zh", "通用", "默认", "quick", "microphone", "audio", List.of(), null);

        assertEquals(60_000, reports.generate(session).durationMs());
    }
}
