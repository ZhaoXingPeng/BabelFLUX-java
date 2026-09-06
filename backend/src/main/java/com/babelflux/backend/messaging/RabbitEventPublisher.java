package com.babelflux.backend.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "rabbitmq-enabled", havingValue = "true")
public class RabbitEventPublisher implements EventPublisher {
    private final RabbitTemplate rabbit;
    public RabbitEventPublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; }
    @Override public void publish(String topic, String payload) { rabbit.convertAndSend(topic, payload); }
}
