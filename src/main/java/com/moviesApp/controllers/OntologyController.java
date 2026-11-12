package com.moviesApp.controllers;

import com.moviesApp.service.OntologyDraftService;
import com.moviesApp.service.OntologyStage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Drafts of the ontology pipeline: one editable, approvable payload per stage and knowledge base. */
@RestController
@RequestMapping("/knowledge/ontology/{label}")
public class OntologyController {

    private final OntologyDraftService drafts;

    public OntologyController(OntologyDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping
    public Map<String, Object> overview(@PathVariable String label) {
        return drafts.overview(label);
    }

    @GetMapping("/{stage}")
    public ResponseEntity<Map<String, Object>> stage(@PathVariable String label, @PathVariable String stage) {
        return OntologyStage.parse(stage)
                .map(s -> ResponseEntity.ok(drafts.stage(label, s)))
                .orElseGet(() -> unknownStage());
    }

    @PutMapping("/{stage}")
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable String label, @PathVariable String stage, @RequestBody Map<String, Object> payload) {
        return OntologyStage.parse(stage)
                .map(s -> ResponseEntity.ok(drafts.save(label, s, payload)))
                .orElseGet(() -> unknownStage());
    }

    @PostMapping("/{stage}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String label, @PathVariable String stage) {
        return OntologyStage.parse(stage).map(s -> {
            Map<String, Object> result = drafts.approve(label, s);
            return Boolean.TRUE.equals(result.get("approved"))
                    ? ResponseEntity.ok(result)
                    : ResponseEntity.unprocessableEntity().body(result);
        }).orElseGet(() -> unknownStage());
    }

    @DeleteMapping
    public Map<String, Object> reset(@PathVariable String label) {
        drafts.reset(label);
        return Map.of("label", label, "reset", true);
    }

    private static ResponseEntity<Map<String, Object>> unknownStage() {
        return ResponseEntity.badRequest().body(Map.of("error", "unknown stage"));
    }
}
