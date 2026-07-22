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

    public static final String POSTER_QUEUE    = "posters.queue";
    public static final String POSTER_EXCHANGE = "posters.exchange";
    public static final String POSTER_KEY      = "posters.generate";

    @Bean
    public Queue postersQueue() {
        return QueueBuilder.durable(POSTER_QUEUE).build();
    }

    @Bean
    public DirectExchange postersExchange() {
        return new DirectExchange(POSTER_EXCHANGE);
    }

    @Bean
    public Binding postersBinding(Queue postersQueue, DirectExchange postersExchange) {
        return BindingBuilder.bind(postersQueue).to(postersExchange).with(POSTER_KEY);
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
