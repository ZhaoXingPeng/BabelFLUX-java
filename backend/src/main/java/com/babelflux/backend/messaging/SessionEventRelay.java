package com.babelflux.backend.messaging;

import com.babelflux.backend.observability.OperationalMetrics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "rabbitmq-enabled", havingValue = "true")
public class SessionEventRelay {
    private static final Logger log = LoggerFactory.getLogger(SessionEventRelay.class);
    private static final long LEASE_SECONDS = 30;
    private final JdbcSessionEventOutbox outbox;
    private final EventPublisher publisher;
    private final OperationalMetrics metrics;
    private final String owner = "relay-" + UUID.randomUUID();

    @Autowired
    public SessionEventRelay(JdbcSessionEventOutbox outbox, EventPublisher publisher, OperationalMetrics metrics) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    public SessionEventRelay(JdbcSessionEventOutbox outbox, EventPublisher publisher) {
        this(outbox, publisher, OperationalMetrics.NOOP);
    }

    @Scheduled(fixedDelayString = "${babelflux.messaging.relay-interval-ms:1000}")
    public void relay() {
        List<JdbcSessionEventOutbox.PendingEvent> events = outbox.pending(100);
        for (JdbcSessionEventOutbox.PendingEvent event : events) {
            if (!outbox.tryClaim(event.eventId(), owner, Instant.now().plusSeconds(LEASE_SECONDS))) continue;
            try {
                publisher.publish(event.eventType(), event.payload());
                outbox.markPublished(event.eventId(), owner);
                metrics.asyncSucceeded("outbox", "publish");
            } catch (RuntimeException error) {
                long backoffSeconds = Math.min(60, 1L << Math.min(event.attempts(), 6));
                String detail = error.getMessage() == null || error.getMessage().isBlank()
                        ? error.getClass().getSimpleName() : error.getMessage();
                outbox.markFailed(event.eventId(), owner, Instant.now().plusSeconds(backoffSeconds), detail);
                metrics.dependencyFailure("rabbitmq");
                metrics.asyncRetry("outbox", "publish");
                log.atWarn()
                        .addKeyValue("event", "async.task.retry")
                        .addKeyValue("component", "outbox")
                        .addKeyValue("operation", "publish")
                        .addKeyValue("attempt", event.attempts() + 1)
                        .addKeyValue("retry_seconds", backoffSeconds)
                        .addKeyValue("error_type", error.getClass().getSimpleName())
                        .log("Outbox publish will be retried");
            }
        }
    }
}
