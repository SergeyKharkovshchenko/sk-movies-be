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

    /**
     * Step 1: suggest sections from raw text (no processing, no storage).
     * FE shows these to the user for review/edit, then calls /knowledge/process.
     * Body: { "text": "..." }
     * Returns: [{ "title": "Early Life", "text": "..." }, ...]
     */
    @PostMapping("/knowledge/suggest-sections")
    public ResponseEntity<?> suggestSections(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        try {
            return ResponseEntity.ok(knowledgeService.suggestSections(text.trim()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Step 2: process confirmed sections (after FE user review).
     * Accepts either:
     *   { "label": "napoleon", "text": "raw text..." }
     *   { "label": "napoleon", "sections": [{"title": "Career", "text": "..."}] }
     *
     * SSE events: section_start, chunk_done, extract_progress, graph_stored,
     *             embed_progress, section_done, complete, error, section_skip
     */
    @PostMapping(value = "/knowledge/process", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter process(@RequestBody Map<String, Object> body) {
        String label = String.valueOf(body.getOrDefault("label", "default")).trim();

        List<Map<String, String>> sections;
        if (body.containsKey("sections")) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> s = (List<Map<String, String>>) body.get("sections");
            sections = s;
        } else {
            String text = (String) body.get("text");
            if (text == null || text.isBlank()) {
                SseEmitter emitter = new SseEmitter();
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"message\":\"Either 'text' or 'sections' is required\"}"));
                    emitter.complete();
                } catch (Exception ignored) {}
                return emitter;
            }
            sections = List.of(Map.of("title", label, "text", text.trim()));
        }

        return knowledgeService.process(sections, label);
    }

    @PostMapping("/napoleon-chat")
    public ResponseEntity<Map<String, Object>> napoleonChat(@RequestBody Map<String, Object> body) {
        try {
            String question = (String) body.get("question");
            String label    = String.valueOf(body.getOrDefault("label", "default"));
            if (question == null || question.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> history = body.containsKey("history")
                    ? (List<Map<String, String>>) body.get("history")
                    : List.of();

            List<Map<String, String>> messages = new java.util.ArrayList<>(history);
            messages.add(Map.of("role", "user", "content", question));

            double temperature = body.containsKey("temperature")
                    ? ((Number) body.get("temperature")).doubleValue() : 0.7;
            int maxTokens = body.containsKey("maxTokens")
                    ? ((Number) body.get("maxTokens")).intValue() : 800;

            return ResponseEntity.ok(
                    knowledgeService.chat(question, label, messages, temperature, maxTokens));
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
