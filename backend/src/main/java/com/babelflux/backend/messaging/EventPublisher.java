package com.babelflux.backend.messaging;

public interface EventPublisher {
    void publish(String topic, String payload);
}
