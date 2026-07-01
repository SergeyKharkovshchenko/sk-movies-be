package com.moviesApp.controllers;

import com.moviesApp.service.KnowledgeService;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping
public class KnowledgeController {

    private static final Set<String> KNOWLEDGE_CACHES = Set.of("suggestGraph", "suggestSections");

    private final KnowledgeService knowledgeService;
    private final CacheManager     cacheManager;

    public KnowledgeController(KnowledgeService knowledgeService, CacheManager cacheManager) {
        this.knowledgeService = knowledgeService;
        this.cacheManager     = cacheManager;
    }

    /**
     * Step 1 (structured): GPT suggests entities, sections assigned to entities,
     * and entity-to-entity relationships. FE shows for review, then calls /knowledge/process-graph.
     * Body: { "text": "..." }
     */
    @PostMapping("/knowledge/suggest-graph")
    public ResponseEntity<?> suggestGraph(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        try {
            String key = text.trim();
            var cache = cacheManager.getCache("suggestGraph");
            boolean wasCached = cache != null && cache.get(key) != null;
            Map<String, Object> result = new LinkedHashMap<>(knowledgeService.suggestGraph(key));
            result.put("cached", wasCached);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Step 2 (structured): process confirmed graph structure via SSE.
     * SSE events: entity_stored, section_start, chunk_done, extract_progress,
     *             graph_stored, embed_progress, section_done, relationships_stored,
     *             entities_embedded, complete, error
     */
    @PostMapping(value = "/knowledge/process-graph", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter processGraph(@RequestBody Map<String, Object> body) {
        String label = String.valueOf(body.getOrDefault("label", "default")).trim();

        @SuppressWarnings("unchecked")
        List<Map<String, String>> entities = body.containsKey("entities")
                ? (List<Map<String, String>>) body.get("entities") : List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> sections = body.containsKey("sections")
                ? (List<Map<String, String>>) body.get("sections") : List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> entityRelationships = body.containsKey("entityRelationships")
                ? (List<Map<String, String>>) body.get("entityRelationships") : List.of();

        return knowledgeService.processGraph(label, entities, sections, entityRelationships);
    }

    /**
     * Step 1 (flat): suggest sections from raw text (no processing, no storage).
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
            String key = text.trim();
            var cache = cacheManager.getCache("suggestSections");
            boolean wasCached = cache != null && cache.get(key) != null;
            List<?> sections = knowledgeService.suggestSections(key);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sections", sections);
            result.put("cached", wasCached);
            return ResponseEntity.ok(result);
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

            double  temperature   = body.containsKey("temperature")   ? ((Number) body.get("temperature")).doubleValue()  : 0.7;
            int     maxTokens     = body.containsKey("maxTokens")     ? ((Number) body.get("maxTokens")).intValue()         : 800;
            int     topK          = body.containsKey("topK")          ? ((Number) body.get("topK")).intValue()              : 0;
            int     neighborLimit = body.containsKey("neighborLimit") ? ((Number) body.get("neighborLimit")).intValue()     : 0;
            String  ragMode       = String.valueOf(body.getOrDefault("ragMode", "combined"));
            boolean strict        = Boolean.TRUE.equals(body.get("strict"));
            String  seedPriority  = String.valueOf(body.getOrDefault("seedPriority", "graph"));

            return ResponseEntity.ok(
                    knowledgeService.chat(question, label, messages, temperature, maxTokens, topK, neighborLimit, ragMode, strict, seedPriority));
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

    /** Clear all knowledge caches (suggestGraph + suggestSections). */
    @DeleteMapping("/knowledge/cache")
    public ResponseEntity<Map<String, Object>> clearAllCaches() {
        List<String> cleared = new ArrayList<>();
        for (String name : KNOWLEDGE_CACHES) {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
                cleared.add(name);
            }
        }
        return ResponseEntity.ok(Map.of("cleared", cleared));
    }

    /** Clear one specific knowledge cache by name. */
    @DeleteMapping("/knowledge/cache/{name}")
    public ResponseEntity<Map<String, Object>> clearCache(@PathVariable String name) {
        if (!KNOWLEDGE_CACHES.contains(name)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown cache '" + name + "'",
                    "available", KNOWLEDGE_CACHES
            ));
        }
        var cache = cacheManager.getCache(name);
        if (cache != null) cache.clear();
        return ResponseEntity.ok(Map.of("cleared", name));
    }
}
