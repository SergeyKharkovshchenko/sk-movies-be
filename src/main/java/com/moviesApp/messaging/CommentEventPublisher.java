package com.moviesApp.messaging;

import com.moviesApp.entities.Comment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CommentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CommentEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public CommentEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(String source, String param, List<Comment> comments) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("source", source);
            message.put("param", param);
            message.put("timestamp", Instant.now().toString());
            message.put("count", comments.size());
            message.put("comments", comments);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY, message);
            log.debug("Published {} comment(s) from {} to queue", comments.size(), source);
        } catch (Exception e) {
            log.warn("Failed to publish comment event [source={}, param={}]: {}", source, param, e.getMessage());
        }
    }
}
