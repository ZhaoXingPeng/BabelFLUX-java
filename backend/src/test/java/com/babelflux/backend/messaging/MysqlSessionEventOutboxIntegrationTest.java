package com.babelflux.backend.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs only when a caller provides an isolated, real MySQL database. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_IT", matches = "true")
class MysqlSessionEventOutboxIntegrationTest {
    @Test
    void persistsFailureDetailAndClearsItAfterSuccessfulRecovery() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                required("TEST_MYSQL_URL"), required("TEST_MYSQL_USERNAME"),
                System.getenv().getOrDefault("TEST_MYSQL_PASSWORD", "")));
        JdbcSessionEventOutbox outbox = new JdbcSessionEventOutbox(jdbc,
                JsonMapper.builder().addModule(new JavaTimeModule()).build());
        String eventId = "mysql-it-" + UUID.randomUUID();
        String owner = "mysql-it-relay";

        try {
            outbox.append(new SessionEvent(eventId, "session.created", 1, "mysql-it-session",
                    Instant.now(), Map.of("status", "created")));
            assertTrue(outbox.tryClaim(eventId, owner, Instant.now().plusSeconds(30)));

            outbox.markFailed(eventId, owner, Instant.now().minusSeconds(1), "broker connection refused");
            Map<String, Object> failed = jdbc.queryForMap("select status, attempts, last_error "
                    + "from babelflux_session_event_outbox where event_id=?", eventId);
            assertEquals("pending", failed.get("status"));
            assertEquals(1, ((Number) failed.get("attempts")).intValue());
            assertEquals("broker connection refused", failed.get("last_error"));

            assertTrue(outbox.tryClaim(eventId, owner, Instant.now().plusSeconds(30)));
            outbox.markPublished(eventId, owner);
            Map<String, Object> published = jdbc.queryForMap("select status, last_error, lease_owner, lease_until "
                    + "from babelflux_session_event_outbox where event_id=?", eventId);
            assertEquals("published", published.get("status"));
            assertEquals(null, published.get("last_error"));
            assertEquals(null, published.get("lease_owner"));
            assertEquals(null, published.get("lease_until"));
        } finally {
            jdbc.update("delete from babelflux_session_event_outbox where event_id=?", eventId);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for MySQL integration tests");
        return value;
    }
}
