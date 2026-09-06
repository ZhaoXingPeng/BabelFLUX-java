package com.babelflux.backend.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Tag;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_RABBITMQ_IT", matches = "true")
class RabbitMqIntegrationTest {
    private static final String ROUTING_KEY = "session.integration";
    private static final String QUEUE = "babelflux.integration.queue";

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    @Test
    void publishesAndConsumesThroughDeclaredExchange() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(
                rabbit.getHost(), rabbit.getAmqpPort());
        connectionFactory.setUsername(rabbit.getAdminUsername());
        connectionFactory.setPassword(rabbit.getAdminPassword());
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        TopicExchange exchange = new TopicExchange(RabbitMessagingConfiguration.EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).build();
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("session.#"));

        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.convertAndSend(RabbitMessagingConfiguration.EXCHANGE, ROUTING_KEY, "integration-payload");

        assertEquals("integration-payload", template.receiveAndConvert(QUEUE, 5_000));
        connectionFactory.destroy();
    }
}
