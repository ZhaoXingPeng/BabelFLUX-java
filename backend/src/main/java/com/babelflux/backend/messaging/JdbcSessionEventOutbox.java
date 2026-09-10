package com.babelflux.backend.messaging;

import com.babelflux.backend.infrastructure.JdbcTemporal;
import com.babelflux.backend.infrastructure.mybatis.SessionEventOutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/** MySQL/H2 outbox used to make session persistence and event publication atomic. */
@Repository
public class JdbcSessionEventOutbox {
    private final SessionEventOutboxMapper statements;
    private final ObjectMapper mapper;

    public JdbcSessionEventOutbox(SessionEventOutboxMapper statements, ObjectMapper mapper) {
        this.statements = statements;
        this.mapper = mapper;
    }

    public void append(SessionEvent event) {
        try {
            Timestamp now = JdbcTemporal.now();
            statements.append(event.eventId(), event.eventType(), event.schemaVersion(), event.sessionId(),
                    Timestamp.from(event.occurredAt()), mapper.writeValueAsString(event), now);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("session event cannot be serialized", error);
        }
    }

    public List<PendingEvent> pending(int limit) {
        Timestamp now = JdbcTemporal.now();
        return statements.pending(now, limit).stream()
                .map(row -> new PendingEvent(row.getEventId(), row.getEventType(), row.getSessionId(),
                        row.getPayload(), row.getAttempts()))
                .toList();
    }

    /**
     * Claims an event for one relay instance. The conditional update makes
     * concurrent relays mutually exclusive while allowing expired leases to recover.
     */
    public boolean tryClaim(String eventId, String owner, Instant leaseUntil) {
        Timestamp now = JdbcTemporal.now();
        return statements.tryClaim(eventId, owner, JdbcTemporal.future(leaseUntil), now) == 1;
    }

    public void markPublished(String eventId, String owner) {
        statements.markPublished(eventId, owner, JdbcTemporal.now());
    }

    public void markFailed(String eventId, String owner, Instant nextAttemptAt, String error) {
        String detail = error == null || error.isBlank() ? "unknown event delivery failure" : error;
        if (detail.length() > 1000) detail = detail.substring(0, 1000);
        Instant reference = Instant.now();
        statements.markFailed(eventId, owner, JdbcTemporal.dueAt(nextAttemptAt, reference), detail);
    }

    public record PendingEvent(String eventId, String eventType, String sessionId,
                               String payload, int attempts) {}
}
