package com.babelflux.backend.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitEventPublisherTest {
    @Test
    void publishesToTheDurableSessionExchangeWithEventTypeRoutingKey() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        new RabbitEventPublisher(rabbit).publish("session.created", "payload");

        verify(rabbit).convertAndSend(RabbitMessagingConfiguration.EXCHANGE,
                "session.created", "payload");
    }
}
