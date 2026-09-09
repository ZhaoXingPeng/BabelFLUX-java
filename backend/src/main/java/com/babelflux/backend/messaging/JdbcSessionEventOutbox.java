package com.babelflux.backend.messaging;

import com.babelflux.backend.infrastructure.JdbcTemporal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** MySQL/H2 outbox used to make session persistence and event publication atomic. */
@Repository
public class JdbcSessionEventOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcSessionEventOutbox(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void append(SessionEvent event) {
        try {
            Timestamp now = JdbcTemporal.now();
            jdbc.update("insert into babelflux_session_event_outbox "
                            + "(event_id, event_type, schema_version, session_id, occurred_at, payload, status, attempts, next_attempt_at) "
                            + "values (?, ?, ?, ?, ?, ?, 'pending', 0, ?)",
                    event.eventId(), event.eventType(), event.schemaVersion(), event.sessionId(),
                    Timestamp.from(event.occurredAt()), mapper.writeValueAsString(event), now);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("session event cannot be serialized", error);
        }
    }

    public List<PendingEvent> pending(int limit) {
        Timestamp now = JdbcTemporal.now();
        return jdbc.query("select event_id, event_type, session_id, payload, attempts "
                        + "from babelflux_session_event_outbox where "
                        + "(status='pending' and next_attempt_at <= ?) "
                        + "or (status='processing' and lease_until is not null "
                        + "and lease_until <= ?) order by created_at limit ?",
                this::map, now, now, limit);
    }

    /**
     * Claims an event for one relay instance. The conditional update makes
     * concurrent relays mutually exclusive while allowing expired leases to recover.
     */
    public boolean tryClaim(String eventId, String owner, Instant leaseUntil) {
        Timestamp now = JdbcTemporal.now();
        return jdbc.update("update babelflux_session_event_outbox set status='processing', lease_owner=?, lease_until=? "
                        + "where event_id=? and ((status='pending' and next_attempt_at <= ?) "
                        + "or (status='processing' and lease_until is not null and lease_until <= ?))",
                owner, JdbcTemporal.future(leaseUntil), eventId, now, now) == 1;
    }

    public void markPublished(String eventId, String owner) {
        jdbc.update("update babelflux_session_event_outbox set status='published', published_at=?, "
                + "last_error=null, lease_owner=null, lease_until=null where event_id=? "
                + "and status='processing' and lease_owner=?",
                JdbcTemporal.now(), eventId, owner);
    }

    public void markFailed(String eventId, String owner, Instant nextAttemptAt, String error) {
        String detail = error == null || error.isBlank() ? "unknown event delivery failure" : error;
        if (detail.length() > 1000) detail = detail.substring(0, 1000);
        Instant reference = Instant.now();
        jdbc.update("update babelflux_session_event_outbox set status='pending', attempts=attempts+1, "
                        + "next_attempt_at=?, last_error=?, lease_owner=null, lease_until=null "
                        + "where event_id=? and status='processing' and lease_owner=?",
                JdbcTemporal.dueAt(nextAttemptAt, reference), detail, eventId, owner);
    }

    private PendingEvent map(ResultSet row, int ignored) throws SQLException {
        return new PendingEvent(row.getString("event_id"), row.getString("event_type"),
                row.getString("session_id"), row.getString("payload"), row.getInt("attempts"));
    }

    public record PendingEvent(String eventId, String eventType, String sessionId,
                               String payload, int attempts) {}
}
