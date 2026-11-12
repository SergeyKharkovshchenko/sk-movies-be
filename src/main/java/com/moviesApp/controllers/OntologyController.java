package com.moviesApp.controllers;

import com.moviesApp.service.OntologyDraftService;
import com.moviesApp.service.OntologyProposalService;
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
    private final OntologyProposalService proposals;

    public OntologyController(OntologyDraftService drafts, OntologyProposalService proposals) {
        this.drafts = drafts;
        this.proposals = proposals;
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

    /** Builds a proposal for the stage from the approved earlier ones and saves it as the stage's draft. */
    @PostMapping("/{stage}/propose")
    public ResponseEntity<Map<String, Object>> propose(@PathVariable String label, @PathVariable String stage) {
        OntologyStage parsed = OntologyStage.parse(stage).orElse(null);
        if (parsed == null) return unknownStage();
        try {
            return ResponseEntity.ok(proposals.propose(label, parsed));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
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
