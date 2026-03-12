package com.moviesApp.controllers;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moviesApp.entities.User;
import com.moviesApp.service.UserService;

@RestController
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping("/getAllUsers")
    public ResponseEntity<Iterable<User>> getAllUsers() {
        Iterable<User> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    // @GetMapping("/createRatingsEmbedding")
    // public ResponseEntity<List<Map<String, Object>>> createRatingsEmbedding() {
    // List<Map<String, Object>> response = userService.createRatingsEmbedding();
    // return ResponseEntity.ok(response);
    // }

    // @GetMapping("/ollamaTest")
    // public ResponseEntity<List<Map<String, Object>>> ollamaTest() {
    // try {
    // List<Map<String, Object>> response = userService.ollamaTest();
    // return ResponseEntity.ok(response);
    // } catch (Exception e) {
    // // log.error("Ollama test failed: {}", e.getMessage(), e);
    // return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    // .body(List.of(Map.of(
    // "error", "Embedding API failed: " + e.getMessage(),
    // "status", "error")));
    // }
    // }

    // @GetMapping("/createRatingsEmbedding")
    // public ResponseEntity<List<Map<String, Object>>> createRatingsEmbedding() {
    // try {
    // List<Map<String, Object>> response = userService.createRatingsEmbedding();
    // return ResponseEntity.ok(response);
    // } catch (Exception e) {
    // // log.error("Ollama test failed: {}", e.getMessage(), e);
    // return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    // .body(List.of(Map.of(
    // "error", "Embedding API failed: " + e.getMessage(),
    // "status", "error")));
    // }
    // }

    @PostMapping("/createRatingsEmbedding") // POST, not GET
    public ResponseEntity<List<Map<String, Object>>> createRatingsEmbeddingSafe() {
        try {
            List<Map<String, Object>> result = userService.createRatingsEmbeddingSafe();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // log.error("API failed", e);
            return ResponseEntity.status(500).body(List.of(Map.of("error", e.getMessage())));
        }
    }

    @PostMapping("/createRatingsEmbedding2") // POST, not GET
    public ResponseEntity<List<Map<String, Object>>> createRatingsEmbeddingSafe2() {
        try {
            List<Map<String, Object>> result = userService.createRatingsEmbeddingSafe2();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // log.error("API failed", e);
            return ResponseEntity.status(500).body(List.of(Map.of("error", e.getMessage())));
        }
    }

    @PostMapping("/createRatingsEmbedding3")
    public ResponseEntity<List<Map<String, Object>>> createRatingsEmbeddingSafe3(
            @RequestParam String userId // Add this parameter
    ) {
        try {
            List<Map<String, Object>> result = userService.createRatingsEmbeddingSafe3(userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // log.error("API failed", e);
            return ResponseEntity.status(500).body(
                    List.of(Map.of("error", e.getMessage(), "userId", userId)));
        }
    }

    @GetMapping("/similar")
    public ResponseEntity<List<Map<String, Object>>> getSimilarUsers(
            @RequestParam String userId,
            @RequestParam(defaultValue = "10") int limit) {

        try {
            // Validate input
            if (userId == null || userId.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(List.of(Map.of("error", "userId required")));
            }

            // List<Map<String, Object>> results = userService.getSimilarUsers(userId,
            // limit, minSimilarity);
            List<Map<String, Object>> results = userService.getSimilarUsers(userId, limit);

            if (results.isEmpty()) {
                return ResponseEntity.ok(List.of(
                        Map.of("message", "No similar users found above similarity threshold", "userId", userId)));
            }

            return ResponseEntity.ok(results);

        } catch (Exception e) {
            // log.error("Failed to get similar users for {}: {}", userId, e.getMessage());
            return ResponseEntity.status(500).body(
                    List.of(Map.of("error", e.getMessage(), "userId", userId)));
        }
    }

}
