package com.moviesApp.controllers;

import com.moviesApp.service.KnowledgeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping(value = "/knowledge/process", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter process(@RequestBody Map<String, String> body) {
        String text  = body.get("text");
        String label = body.getOrDefault("label", "default");
        if (text == null || text.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"text is required\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        return knowledgeService.process(text.trim(), label.trim());
    }

    @PostMapping("/napoleon-chat")
    public ResponseEntity<Map<String, Object>> napoleonChat(@RequestBody Map<String, Object> body) {
        try {
            String question = (String) body.get("question");
            String label    = (String) body.getOrDefault("label", "default");
            if (question == null || question.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> history = body.containsKey("history")
                    ? (List<Map<String, String>>) body.get("history")
                    : List.of();

            // Add current question to history for OpenAI
            List<Map<String, String>> messages = new java.util.ArrayList<>(history);
            messages.add(Map.of("role", "user", "content", question));

            double temperature = body.containsKey("temperature")
                    ? ((Number) body.get("temperature")).doubleValue() : 0.7;
            int maxTokens = body.containsKey("maxTokens")
                    ? ((Number) body.get("maxTokens")).intValue() : 800;

            Map<String, Object> result = knowledgeService.chat(
                    question, label, messages, temperature, maxTokens);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @DeleteMapping("/knowledge/{label}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String label) {
        try {
            return ResponseEntity.ok(knowledgeService.delete(label));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @GetMapping("/knowledge/status")
    public ResponseEntity<Map<String, Object>> status() {
        try {
            return ResponseEntity.ok(knowledgeService.status());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @GetMapping("/knowledge/labels")
    public ResponseEntity<List<String>> labels() {
        try {
            return ResponseEntity.ok(knowledgeService.labels());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
