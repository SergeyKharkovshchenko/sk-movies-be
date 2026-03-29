package com.moviesApp.controllers;

import com.moviesApp.service.PosterEmbeddingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class PosterController {

    private final PosterEmbeddingService posterEmbeddingService;

    public PosterController(PosterEmbeddingService posterEmbeddingService) {
        this.posterEmbeddingService = posterEmbeddingService;
    }

    @GetMapping("/poster/{movieId}")
    public ResponseEntity<Map<String, Object>> getPoster(@PathVariable String movieId) {
        Map<String, Object> result = posterEmbeddingService.getPosterInfo(movieId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/poster-similarity/{movieId}")
    public ResponseEntity<?> getPosterSimilarity(@PathVariable String movieId) {
        try {
            List<Map<String, Object>> result = posterEmbeddingService.getPosterSimilarities(movieId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/poster-embedding/{movieId}")
    public ResponseEntity<Map<String, Object>> getPosterEmbedding(@PathVariable String movieId) {
        try {
            Map<String, Object> result = posterEmbeddingService.getOrCreatePosterEmbedding(movieId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
