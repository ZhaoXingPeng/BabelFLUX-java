package com.babelflux.backend.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "rabbitmq-enabled", havingValue = "true")
public class SessionEventListener {
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public SessionEventListener(ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @RabbitListener(queues = RabbitMessagingConfiguration.QUEUE,
            containerFactory = "sessionEventListenerContainerFactory")
    public void consume(String payload) throws Exception {
        SessionEvent event = mapper.readValue(payload, SessionEvent.class);
        try {
            jdbc.update("insert into babelflux_session_event_receipts "
                            + "(event_id, event_type, session_id, received_at) values (?, ?, ?, ?)",
                    event.eventId(), event.eventType(), event.sessionId(), Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            // Redelivery is expected; the unique event_id makes the consumer idempotent.
        }
    }
}
