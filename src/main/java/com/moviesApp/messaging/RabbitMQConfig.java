package com.moviesApp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String QUEUE    = "comments.queue";
    public static final String EXCHANGE = "comments.exchange";
    public static final String KEY      = "comments";

    @Bean
    public Queue commentsQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public DirectExchange commentsExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Binding commentsBinding(Queue commentsQueue, DirectExchange commentsExchange) {
        return BindingBuilder.bind(commentsQueue).to(commentsExchange).with(KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}
