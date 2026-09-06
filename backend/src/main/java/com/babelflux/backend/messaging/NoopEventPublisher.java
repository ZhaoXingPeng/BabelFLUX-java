package com.babelflux.backend.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "rabbitmq-enabled", havingValue = "false", matchIfMissing = true)
public class NoopEventPublisher implements EventPublisher {
    @Override public void publish(String topic, String payload) { }
}
