package com.babelflux.backend.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.service.SessionReportService;
import org.junit.jupiter.api.Test;

class SessionEventFactoryTest {
    @Test
    void createsStableEventTypesAndCarriesReportMetadata() {
        Session session = Session.create("session-1", "test", "en", "zh", "general",
                "default", "quick", "demo", "demo", null, "idle", false, java.util.List.of());
        SessionEventFactory factory = new SessionEventFactory();

        SessionEvent created = factory.created(session);
        assertEquals("session.created", created.eventType());
        assertEquals("session-1", created.sessionId());
        assertEquals("en", created.data().get("sourceLanguage"));

        session.end();
        var report = new SessionReportService().generate(session);
        SessionEvent reportEvent = factory.reportGenerated(session, report);
        assertEquals("report.generated", reportEvent.eventType());
        assertEquals("skipped", reportEvent.data().get("correctionStatus"));
    }
}
