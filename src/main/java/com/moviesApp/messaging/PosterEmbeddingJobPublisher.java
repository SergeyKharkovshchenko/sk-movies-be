package com.moviesApp.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PosterEmbeddingJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(PosterEmbeddingJobPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public PosterEmbeddingJobPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(String movieId, String movieTitle, String posterUrl) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("movieId", movieId);
        job.put("movieTitle", movieTitle);
        job.put("posterUrl", posterUrl);
        rabbitTemplate.convertAndSend(RabbitMQConfig.POSTER_EXCHANGE, RabbitMQConfig.POSTER_KEY, job);
        log.debug("Queued poster embedding job for movieId={}", movieId);
    }
}
