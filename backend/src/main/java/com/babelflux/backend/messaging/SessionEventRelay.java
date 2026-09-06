package com.babelflux.backend.messaging;

import java.time.Instant;
import java.util.List;
import org.springframework.amqp.AmqpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "rabbitmq-enabled", havingValue = "true")
public class SessionEventRelay {
    private final JdbcSessionEventOutbox outbox;
    private final EventPublisher publisher;

    public SessionEventRelay(JdbcSessionEventOutbox outbox, EventPublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${babelflux.messaging.relay-interval-ms:1000}")
    public void relay() {
        List<JdbcSessionEventOutbox.PendingEvent> events = outbox.pending(100);
        for (JdbcSessionEventOutbox.PendingEvent event : events) {
            try {
                publisher.publish(event.eventType(), event.payload());
                outbox.markPublished(event.eventId());
            } catch (AmqpException | IllegalStateException error) {
                long backoffSeconds = Math.min(60, 1L << Math.min(event.attempts(), 6));
                outbox.markFailed(event.eventId(), Instant.now().plusSeconds(backoffSeconds));
            }
        }
    }
}
