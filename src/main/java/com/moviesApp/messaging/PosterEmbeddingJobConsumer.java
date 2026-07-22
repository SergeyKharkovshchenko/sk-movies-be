package com.moviesApp.messaging;

import com.moviesApp.service.PosterEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PosterEmbeddingJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(PosterEmbeddingJobConsumer.class);

    private final PosterEmbeddingService posterEmbeddingService;

    public PosterEmbeddingJobConsumer(PosterEmbeddingService posterEmbeddingService) {
        this.posterEmbeddingService = posterEmbeddingService;
    }

    @RabbitListener(queues = RabbitMQConfig.POSTER_QUEUE)
    public void handle(Map<String, Object> job) {
        String movieId = (String) job.get("movieId");
        String movieTitle = (String) job.get("movieTitle");
        String posterUrl = (String) job.get("posterUrl");
        try {
            posterEmbeddingService.processPosterEmbeddingJob(movieId, movieTitle, posterUrl);
            log.debug("Processed poster embedding job for movieId={}", movieId);
        } catch (Exception e) {
            log.warn("Failed to process poster embedding job [movieId={}]: {}", movieId, e.getMessage());
        }
    }
}
