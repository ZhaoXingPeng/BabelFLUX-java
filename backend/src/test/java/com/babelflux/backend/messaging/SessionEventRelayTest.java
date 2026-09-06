package com.babelflux.backend.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

class SessionEventRelayTest {
    @Test
    void marksPublishedAfterSuccessfulDelivery() {
        JdbcSessionEventOutbox outbox = mock(JdbcSessionEventOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        var event = new JdbcSessionEventOutbox.PendingEvent("e-1", "session.created", "s-1", "{}", 0);
        when(outbox.pending(100)).thenReturn(List.of(event));
        when(outbox.tryClaim(any(String.class), any(String.class), any(java.time.Instant.class))).thenReturn(true);

        new SessionEventRelay(outbox, publisher).relay();

        verify(publisher).publish("session.created", "{}");
        verify(outbox).markPublished(eq("e-1"), any(String.class));
    }

    @Test
    void schedulesRetryAfterBrokerFailure() {
        JdbcSessionEventOutbox outbox = mock(JdbcSessionEventOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        var event = new JdbcSessionEventOutbox.PendingEvent("e-2", "session.finished", "s-1", "{}", 2);
        when(outbox.pending(100)).thenReturn(List.of(event));
        when(outbox.tryClaim(any(String.class), any(String.class), any(java.time.Instant.class))).thenReturn(true);
        org.mockito.Mockito.doThrow(new AmqpException("broker unavailable"))
                .when(publisher).publish("session.finished", "{}");

        new SessionEventRelay(outbox, publisher).relay();

        verify(outbox).markFailed(any(String.class), any(String.class), any(java.time.Instant.class));
    }

    @Test
    void skipsAnEventAlreadyClaimedByAnotherRelay() {
        JdbcSessionEventOutbox outbox = mock(JdbcSessionEventOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        var event = new JdbcSessionEventOutbox.PendingEvent("e-3", "session.created", "s-1", "{}", 0);
        when(outbox.pending(100)).thenReturn(List.of(event));
        when(outbox.tryClaim(any(String.class), any(String.class), any(java.time.Instant.class))).thenReturn(false);

        new SessionEventRelay(outbox, publisher).relay();

        verify(publisher, org.mockito.Mockito.never()).publish(any(String.class), any(String.class));
    }
}
