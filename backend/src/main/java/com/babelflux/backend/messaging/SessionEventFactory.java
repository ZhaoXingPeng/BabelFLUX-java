package com.babelflux.backend.messaging;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SessionEventFactory {
    public SessionEvent created(Session session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", session.getStatus());
        data.put("sourceLanguage", session.getSourceLanguage());
        data.put("targetLanguage", session.getTargetLanguage());
        data.put("productMode", session.getProductMode());
        data.put("inputMode", session.getInputMode());
        return event("session.created", session.getId(), data);
    }

    public SessionEvent finished(Session session, SessionReport report) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", session.getStatus());
        data.put("reportId", report.reportId());
        data.put("durationMs", report.durationMs());
        data.put("segmentCount", report.metrics().segments());
        return event("session.finished", session.getId(), data);
    }

    public SessionEvent reportGenerated(Session session, SessionReport report) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportId", report.reportId());
        data.put("correctionStatus", report.correctionStatus());
        return event("report.generated", session.getId(), data);
    }

    private static SessionEvent event(String type, String sessionId, Map<String, Object> data) {
        return new SessionEvent(UUID.randomUUID().toString(), type, 1, sessionId, Instant.now(), data);
    }
}
