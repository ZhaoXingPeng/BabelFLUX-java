package com.babelflux.backend.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler.DefaultExceptionStrategy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "rabbitmq-enabled", havingValue = "true")
public class RabbitMessagingConfiguration {
    public static final String EXCHANGE = "babelflux.session.events";
    public static final String QUEUE = "babelflux.session.events.v1";
    public static final String DLX = "babelflux.session.events.dlx";
    public static final String DLQ = "babelflux.session.events.dlq";

    @Bean TopicExchange sessionEventsExchange() { return new TopicExchange(EXCHANGE, true, false); }
    @Bean TopicExchange sessionEventsDeadLetterExchange() { return new TopicExchange(DLX, true, false); }
    @Bean Queue sessionEventsQueue() {
        return QueueBuilder.durable(QUEUE).withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ).build();
    }
    @Bean Queue sessionEventsDeadLetterQueue() { return QueueBuilder.durable(DLQ).build(); }
    @Bean Binding sessionEventsBinding(
            @Qualifier("sessionEventsQueue") Queue sessionEventsQueue,
            @Qualifier("sessionEventsExchange") TopicExchange sessionEventsExchange) {
        return BindingBuilder.bind(sessionEventsQueue).to(sessionEventsExchange).with("session.#");
    }
    @Bean Binding sessionEventsDeadLetterBinding(
                                                 @Qualifier("sessionEventsDeadLetterQueue") Queue sessionEventsDeadLetterQueue,
                                                 @Qualifier("sessionEventsDeadLetterExchange") TopicExchange sessionEventsDeadLetterExchange) {
        return BindingBuilder.bind(sessionEventsDeadLetterQueue).to(sessionEventsDeadLetterExchange).with(DLQ);
    }
    @Bean SimpleRabbitListenerContainerFactory sessionEventListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setErrorHandler(new ConditionalRejectingErrorHandler(new DefaultExceptionStrategy()));
        return factory;
    }
}
