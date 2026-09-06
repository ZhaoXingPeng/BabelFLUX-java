package com.babelflux.backend.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcSessionEventOutboxTest {
    @Test
    void persistsPendingEventAndTransitionsItsDeliveryState() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:event-outbox;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table babelflux_session_event_outbox ("
                + "event_id varchar(64) primary key, event_type varchar(128) not null, schema_version int not null, "
                + "session_id varchar(64) not null, occurred_at timestamp not null, payload text not null, "
                + "status varchar(16) not null, attempts int not null, next_attempt_at timestamp not null, "
                + "created_at timestamp default current_timestamp, published_at timestamp, "
                + "lease_owner varchar(128), lease_until timestamp)");
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JdbcSessionEventOutbox outbox = new JdbcSessionEventOutbox(jdbc, mapper);
        SessionEvent event = new SessionEvent("event-1", "session.created", 1, "session-1",
                Instant.now(), Map.of("status", "created"));

        outbox.append(event);
        assertEquals(1, outbox.pending(10).size());
        assertTrue(outbox.tryClaim("event-1", "relay-a", Instant.now().plusSeconds(30)));
        outbox.markFailed("event-1", "relay-a", Instant.now().minusSeconds(1));
        assertEquals(1, outbox.pending(10).getFirst().attempts());
        assertTrue(outbox.tryClaim("event-1", "relay-a", Instant.now().plusSeconds(30)));
        outbox.markPublished("event-1", "relay-a");
        assertTrue(outbox.pending(10).isEmpty());
    }

    @Test
    void onlyOneRelayCanClaimAnEventAtATime() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:event-outbox-claim;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table babelflux_session_event_outbox ("
                + "event_id varchar(64) primary key, event_type varchar(128) not null, schema_version int not null, "
                + "session_id varchar(64) not null, occurred_at timestamp not null, payload text not null, "
                + "status varchar(16) not null, attempts int not null, next_attempt_at timestamp not null, "
                + "created_at timestamp default current_timestamp, published_at timestamp, "
                + "lease_owner varchar(128), lease_until timestamp)");
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JdbcSessionEventOutbox outbox = new JdbcSessionEventOutbox(jdbc, mapper);
        outbox.append(new SessionEvent("event-claim", "session.created", 1, "session-1",
                Instant.now(), Map.of("status", "created")));

        assertTrue(outbox.tryClaim("event-claim", "relay-a", Instant.now().plusSeconds(30)));
        assertFalse(outbox.tryClaim("event-claim", "relay-b", Instant.now().plusSeconds(30)));
        outbox.markPublished("event-claim", "relay-a");
        assertTrue(outbox.pending(10).isEmpty());
    }

    @Test
    void expiredLeaseCanBeRecoveredByAnotherRelay() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:event-outbox-recovery;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table babelflux_session_event_outbox ("
                + "event_id varchar(64) primary key, event_type varchar(128) not null, schema_version int not null, "
                + "session_id varchar(64) not null, occurred_at timestamp not null, payload text not null, "
                + "status varchar(16) not null, attempts int not null, next_attempt_at timestamp not null, "
                + "created_at timestamp default current_timestamp, published_at timestamp, "
                + "lease_owner varchar(128), lease_until timestamp)");
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JdbcSessionEventOutbox outbox = new JdbcSessionEventOutbox(jdbc, mapper);
        outbox.append(new SessionEvent("event-recovery", "session.finished", 1, "session-1",
                Instant.now(), Map.of("status", "finished")));

        assertTrue(outbox.tryClaim("event-recovery", "relay-a", Instant.now().minusSeconds(1)));
        assertTrue(outbox.pending(10).stream().anyMatch(event -> event.eventId().equals("event-recovery")));
        assertTrue(outbox.tryClaim("event-recovery", "relay-b", Instant.now().plusSeconds(30)));
        outbox.markPublished("event-recovery", "relay-b");
        assertTrue(outbox.pending(10).isEmpty());
    }
}
