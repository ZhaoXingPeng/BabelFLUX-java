package com.babelflux.backend.messaging;

import com.babelflux.backend.infrastructure.mybatis.SessionEventReceiptMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "rabbitmq-enabled", havingValue = "true")
public class SessionEventListener {
    private final ObjectMapper mapper;
    private final SessionEventReceiptMapper receipts;

    public SessionEventListener(ObjectMapper mapper, SessionEventReceiptMapper receipts) {
        this.mapper = mapper;
        this.receipts = receipts;
    }

    @RabbitListener(queues = RabbitMessagingConfiguration.QUEUE,
            containerFactory = "sessionEventListenerContainerFactory")
    public void consume(String payload) throws Exception {
        SessionEvent event = mapper.readValue(payload, SessionEvent.class);
        try {
            receipts.record(event.eventId(), event.eventType(), event.sessionId(), Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            // Redelivery is expected; the unique event_id makes the consumer idempotent.
        }
    }
}
