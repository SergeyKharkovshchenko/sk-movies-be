package com.moviesApp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesApp.entities.KgDraft;
import com.moviesApp.repositories.KgDraftRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Saves each stage of an unpublished knowledge-base build and enforces the order: a stage can be
 * approved only when the previous one is, and changing an approved stage marks everything after
 * it stale so nothing downstream silently rests on outdated input.
 */
@Service
public class OntologyDraftService {

    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";
    public static final String STALE = "STALE";
    public static final String EMPTY = "EMPTY";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final KgDraftRepository repository;
    private final ObjectMapper mapper;

    public OntologyDraftService(KgDraftRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Every stage with its status and payload (EMPTY when nothing was saved yet). */
    public Map<String, Object> overview(String label) {
        Map<String, KgDraft> byStage = new LinkedHashMap<>();
        repository.findByLabel(label).forEach(d -> byStage.put(d.getStage(), d));
        List<Map<String, Object>> stages = new ArrayList<>();
        for (OntologyStage stage : OntologyStage.values()) {
            stages.add(entry(stage, byStage.get(stage.key())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("stages", stages);
        return out;
    }

    public Map<String, Object> stage(String label, OntologyStage stage) {
        return entry(stage, repository.findByLabelAndStage(label, stage.key()).orElse(null));
    }

    /** Payloads of the stages that were approved, keyed by stage. */
    public Map<OntologyStage, Map<String, Object>> approvedPayloads(String label) {
        Map<OntologyStage, Map<String, Object>> out = new EnumMap<>(OntologyStage.class);
        for (KgDraft d : repository.findByLabel(label)) {
            if (!APPROVED.equals(d.getStatus())) continue;
            OntologyStage.parse(d.getStage()).ifPresent(s -> out.put(s, read(d.getPayload())));
        }
        return out;
    }

    /**
     * Stores the stage as the user edited it (status DRAFT). A real change to a stage later stages
     * depend on marks them STALE. The answer carries what still blocks approval.
     */
    public Map<String, Object> save(String label, OntologyStage stage, Map<String, Object> payload) {
        String json = write(payload);
        KgDraft draft = repository.findByLabelAndStage(label, stage.key()).orElseGet(() -> {
            KgDraft d = new KgDraft();
            d.setLabel(label);
            d.setStage(stage.key());
            d.setVersion(0);
            return d;
        });
        boolean isNew = draft.getPayload() == null;
        boolean changed = !json.equals(draft.getPayload());
        if (changed) {
            draft.setPayload(json);
            draft.setVersion(draft.getVersion() + 1);
            draft.setStatus(DRAFT);
            draft.setUpdatedAt(Instant.now());
            repository.save(draft);
            if (!isNew) markLaterStale(label, stage);
        }
        Map<String, Object> out = new LinkedHashMap<>(entry(stage, draft));
        out.put("errors", OntologyRules.validate(stage, payload, approvedPayloads(label)));
        return out;
    }

    /** The stage after validation: approved, or the reasons it cannot be. */
    public Map<String, Object> approve(String label, OntologyStage stage) {
        Optional<KgDraft> found = repository.findByLabelAndStage(label, stage.key());
        List<String> errors = new ArrayList<>();
        if (found.isEmpty()) {
            errors.add("Nothing to approve: this stage has not been saved yet.");
        } else {
            Map<OntologyStage, Map<String, Object>> approved = approvedPayloads(label);
            stage.previous().ifPresent(prev -> {
                if (!approved.containsKey(prev)) errors.add("Approve the " + prev.key() + " stage first.");
            });
            if (errors.isEmpty()) errors.addAll(OntologyRules.validate(stage, read(found.get().getPayload()), approved));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!errors.isEmpty()) {
            out.put("approved", false);
            out.put("errors", errors);
            return out;
        }
        KgDraft draft = found.get();
        draft.setStatus(APPROVED);
        draft.setUpdatedAt(Instant.now());
        repository.save(draft);
        out.put("approved", true);
        out.put("errors", List.of());
        out.putAll(entry(stage, draft));
        return out;
    }

    public void reset(String label) {
        repository.deleteByLabel(label);
    }

    private void markLaterStale(String label, OntologyStage changed) {
        for (OntologyStage later : changed.laterStages()) {
            repository.findByLabelAndStage(label, later.key()).ifPresent(d -> {
                if (!STALE.equals(d.getStatus())) {
                    d.setStatus(STALE);
                    d.setUpdatedAt(Instant.now());
                    repository.save(d);
                }
            });
        }
    }

    private Map<String, Object> entry(OntologyStage stage, KgDraft d) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("stage", stage.key());
        e.put("status", d == null ? EMPTY : d.getStatus());
        e.put("version", d == null ? 0 : d.getVersion());
        e.put("updatedAt", d == null ? null : d.getUpdatedAt().toString());
        e.put("payload", d == null ? Map.of() : read(d.getPayload()));
        return e;
    }

    private Map<String, Object> read(String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored draft is not valid JSON", ex);
        }
    }

    private String write(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Payload is not valid JSON", ex);
        }
    }
}
