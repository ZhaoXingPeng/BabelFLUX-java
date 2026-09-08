package com.babelflux.backend.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "rabbitmq-enabled", havingValue = "true")
public class SessionEventRelay {
    private static final Logger log = LoggerFactory.getLogger(SessionEventRelay.class);
    private static final long LEASE_SECONDS = 30;
    private final JdbcSessionEventOutbox outbox;
    private final EventPublisher publisher;
    private final String owner = "relay-" + UUID.randomUUID();

    public SessionEventRelay(JdbcSessionEventOutbox outbox, EventPublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${babelflux.messaging.relay-interval-ms:1000}")
    public void relay() {
        List<JdbcSessionEventOutbox.PendingEvent> events = outbox.pending(100);
        for (JdbcSessionEventOutbox.PendingEvent event : events) {
            if (!outbox.tryClaim(event.eventId(), owner, Instant.now().plusSeconds(LEASE_SECONDS))) continue;
            try {
                publisher.publish(event.eventType(), event.payload());
                outbox.markPublished(event.eventId(), owner);
            } catch (RuntimeException error) {
                long backoffSeconds = Math.min(60, 1L << Math.min(event.attempts(), 6));
                String detail = error.getMessage() == null || error.getMessage().isBlank()
                        ? error.getClass().getSimpleName() : error.getMessage();
                outbox.markFailed(event.eventId(), owner, Instant.now().plusSeconds(backoffSeconds), detail);
                log.warn("session event relay failed eventId={} type={} attempt={} nextRetrySeconds={}",
                        event.eventId(), event.eventType(), event.attempts() + 1, backoffSeconds, error);
            }
        }
    }
}
