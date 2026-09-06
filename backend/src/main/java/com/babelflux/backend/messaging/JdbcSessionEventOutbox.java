package com.babelflux.backend.messaging;

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
            jdbc.update("insert into babelflux_session_event_outbox "
                            + "(event_id, event_type, schema_version, session_id, occurred_at, payload, status, attempts, next_attempt_at) "
                            + "values (?, ?, ?, ?, ?, ?, 'pending', 0, current_timestamp)",
                    event.eventId(), event.eventType(), event.schemaVersion(), event.sessionId(),
                    Timestamp.from(event.occurredAt()), mapper.writeValueAsString(event));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("session event cannot be serialized", error);
        }
    }

    public List<PendingEvent> pending(int limit) {
        return jdbc.query("select event_id, event_type, session_id, payload, attempts "
                        + "from babelflux_session_event_outbox where status='pending' "
                        + "and next_attempt_at <= current_timestamp order by created_at limit ?",
                this::map, limit);
    }

    public void markPublished(String eventId) {
        jdbc.update("update babelflux_session_event_outbox set status='published', published_at=current_timestamp "
                + "where event_id=? and status='pending'", eventId);
    }

    public void markFailed(String eventId, Instant nextAttemptAt) {
        jdbc.update("update babelflux_session_event_outbox set attempts=attempts+1, next_attempt_at=? "
                + "where event_id=? and status='pending'", Timestamp.from(nextAttemptAt), eventId);
    }

    private PendingEvent map(ResultSet row, int ignored) throws SQLException {
        return new PendingEvent(row.getString("event_id"), row.getString("event_type"),
                row.getString("session_id"), row.getString("payload"), row.getInt("attempts"));
    }

    public record PendingEvent(String eventId, String eventType, String sessionId,
                               String payload, int attempts) {}
}
