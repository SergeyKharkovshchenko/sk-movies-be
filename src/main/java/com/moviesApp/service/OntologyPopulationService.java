package com.moviesApp.service;

import com.moviesApp.rag.EntityExtractorService;
import com.moviesApp.rag.EntityExtractorService.ExtractionResult;
import com.moviesApp.rag.EntityExtractorService.Triple;
import com.moviesApp.service.ModelReconciler.Plan;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.moviesApp.service.OntologyRules.listOf;
import static com.moviesApp.service.OntologyRules.str;

/**
 * Builds the graph from the approved model. Text: the LLM extracts triples under the strict
 * ontology, and each one is checked again here; what fails goes to a review list instead of the
 * graph. CSV: the deterministic importer runs and the result is then reconciled with the model.
 * Both end the same way: reconcile, derive category edges, store the ontology, mirror.
 */
@Service
public class OntologyPopulationService {

    private static final Logger log = LoggerFactory.getLogger(OntologyPopulationService.class);

    private final OntologyDraftService drafts;
    private final KnowledgeService knowledge;
    private final OntologyGraphWriter writer;
    private final EntityExtractorService extractor;

    public OntologyPopulationService(OntologyDraftService drafts, KnowledgeService knowledge,
                                     OntologyGraphWriter writer, EntityExtractorService extractor) {
        this.drafts = drafts;
        this.knowledge = knowledge;
        this.writer = writer;
        this.extractor = extractor;
    }

    /** A triple as resolved to vocabulary terms, or the reason it is not allowed. */
    record Decision(String subject, String predicate, String object, String reason) {
        boolean accepted() {
            return reason == null;
        }
    }

    static Decision decide(Triple t, Map<String, Object> vocabulary, Map<String, Object> ontology) {
        String s = OntologyRules.resolve(vocabulary, t.subject());
        String o = OntologyRules.resolve(vocabulary, t.object());
        String reason = OntologyRules.checkTriple(t.subject(), t.predicate(), t.object(), vocabulary, ontology);
        return new Decision(s == null ? t.subject() : s, t.predicate(), o == null ? t.object() : o, reason);
    }

    /** "PREDICATE (Domain -> Range)" lines for the extraction prompt. */
    static List<String> allowedRelations(Map<String, Object> ontology) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : listOf(ontology, "relations")) {
            out.add(str(r, "name") + " (" + str(r, "domain") + " -> " + str(r, "range") + ")");
        }
        return out;
    }

    /** Every spelling the vocabulary knows (terms and aliases), so extraction keeps the text's own spelling. */
    static List<String> surfaceForms(Map<String, Object> vocabulary) {
        Set<String> forms = new LinkedHashSet<>();
        for (Map<String, Object> t : listOf(vocabulary, "terms")) {
            if (!str(t, "term").isEmpty()) forms.add(str(t, "term"));
            forms.addAll(OntologyRules.strings(t.get("aliases")));
        }
        return List.copyOf(forms);
    }

    public SseEmitter populate(String label, boolean replace) {
        SseEmitter emitter = new SseEmitter(900_000L);
        knowledge.executor().submit(() -> {
            try {
                run(label, replace, emitter);
            } catch (Exception e) {
                log.warn("[ontology] population of '{}' failed: {}", label, e.getMessage(), e);
                try {
                    knowledge.send(emitter, "error", Map.of("message",
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    emitter.completeWithError(e);
                } catch (IOException ignored) { /* client gone */ }
            }
        });
        return emitter;
    }

    private void run(String label, boolean replace, SseEmitter emitter) throws Exception {
        Map<OntologyStage, Map<String, Object>> approved = drafts.approvedPayloads(label);
        for (OntologyStage needed : List.of(OntologyStage.SOURCE, OntologyStage.VOCABULARY, OntologyStage.METADATA,
                OntologyStage.TAXONOMY, OntologyStage.ONTOLOGY)) {
            if (!approved.containsKey(needed)) {
                knowledge.send(emitter, "error", Map.of("message", "Approve the " + needed.key() + " stage first."));
                emitter.complete();
                return;
            }
        }
        long existing = knowledge.countNodes(label);
        if (existing > 0 && !replace) {
            knowledge.send(emitter, "already_exists", Map.of("label", label, "nodes", existing,
                    "message", "This knowledge base already has a graph. Run again with replace to rebuild it."));
            emitter.complete();
            return;
        }
        if (existing > 0) knowledge.delete(label);

        Map<String, Object> source = approved.get(OntologyStage.SOURCE);
        Map<String, Object> vocabulary = approved.get(OntologyStage.VOCABULARY);
        Map<String, Object> ontology = approved.get(OntologyStage.ONTOLOGY);

        List<Map<String, Object>> rejected = new ArrayList<>();
        int accepted = 0;
        if (OntologyProposalService.isCsv(source)) {
            knowledge.send(emitter, "stage_start", Map.of("stage", "csv_import"));
            List<Map<String, String>> files = new ArrayList<>();
            for (Map<String, Object> f : listOf(source, "files")) {
                files.add(Map.of("name", str(f, "name"), "content", f.get("content") == null ? "" : String.valueOf(f.get("content"))));
            }
            Map<String, Object> imported = knowledge.importCsv(label, files);
            knowledge.send(emitter, "csv_imported", Map.of(
                    "entityRows", imported.getOrDefault("entityRows", 0),
                    "relationshipsCreated", imported.getOrDefault("relationshipsCreated", 0)));
        } else {
            accepted = populateText(label, source, vocabulary, ontology, emitter, rejected);
        }

        knowledge.send(emitter, "stage_start", Map.of("stage", "reconcile"));
        Plan plan = ModelReconciler.plan(writer.snapshot(label), vocabulary, approved.get(OntologyStage.TAXONOMY),
                ontology, approved.get(OntologyStage.METADATA));
        Map<String, Object> counts = writer.apply(label, plan);
        for (ModelReconciler.EdgeRemoval r : plan.deleteEdges()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subject", r.edge().from());
            item.put("predicate", r.edge().type());
            item.put("object", r.edge().to());
            item.put("reason", r.reason());
            rejected.add(item);
        }
        knowledge.send(emitter, "reconciled", counts);

        knowledge.inferHierarchyRelationships(label);
        knowledge.embedInferredCategorySentences(label);
        writer.publishOntology(label, ontology);
        knowledge.mirror(label);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("finishedAt", Instant.now().toString());
        summary.put("accepted", accepted);
        summary.put("rejected", rejected);
        summary.put("reconcile", counts);
        drafts.save(label, OntologyStage.POPULATION, summary);
        drafts.approve(label, OntologyStage.POPULATION);

        knowledge.send(emitter, "complete", Map.of("label", label, "nodes", knowledge.countNodes(label),
                "accepted", accepted, "rejected", rejected.size()));
        emitter.complete();
    }

    private int populateText(String label, Map<String, Object> source, Map<String, Object> vocabulary,
                             Map<String, Object> ontology, SseEmitter emitter,
                             List<Map<String, Object>> rejected) throws Exception {
        knowledge.send(emitter, "stage_start", Map.of("stage", "vocabulary"));
        writer.upsertTerms(label, listOf(vocabulary, "terms"), "vocabulary");
        knowledge.send(emitter, "terms_written", Map.of("count", listOf(vocabulary, "terms").size()));

        List<Map<String, Object>> sections = listOf(vocabulary, "sections");
        if (sections.isEmpty() && !str(source, "text").isEmpty()) {
            sections = List.of(Map.of("title", label, "text", str(source, "text")));
        }
        List<String> allowed = allowedRelations(ontology);
        List<String> forms = surfaceForms(vocabulary);

        Set<String> seen = new LinkedHashSet<>();
        int accepted = 0;
        for (int si = 0; si < sections.size(); si++) {
            Map<String, Object> sec = sections.get(si);
            String title = str(sec, "title").isEmpty() ? "section_" + (si + 1) : str(sec, "title");
            String text = str(sec, "text");
            String forEntity = OntologyRules.resolve(vocabulary, str(sec, "forEntity"));
            knowledge.send(emitter, "section_start", Map.of("section", title, "index", si + 1, "total", sections.size()));
            try (Session session = knowledgeSession()) {
                knowledge.createSectionSubNode(title, forEntity == null ? "" : forEntity,
                        str(sec, "sectionRelationship").isEmpty() ? "HAS_INFO" : str(sec, "sectionRelationship"),
                        label, text, session);
            }
            if (text.isBlank()) continue;

            List<String> chunks = knowledge.chunk(text);
            List<String> chunkIds = new ArrayList<>();
            Set<String> entities = new LinkedHashSet<>();
            int sectionAccepted = 0;
            try (Session session = knowledgeSession()) {
                for (int ci = 0; ci < chunks.size(); ci++) {
                    String chunkId = knowledge.storeChunkNode(chunks.get(ci), ci, title, label, session);
                    chunkIds.add(chunkId);
                    ExtractionResult extracted = extractor.extract(chunks.get(ci), forms, allowed);
                    for (Triple t : extracted.triples()) {
                        Decision d = decide(t, vocabulary, ontology);
                        String evidence = extracted.evidence().getOrDefault(ExtractionResult.evidenceKey(t), "");
                        if (d.accepted()) {
                            if (!seen.add(d.subject() + "|" + d.predicate() + "|" + d.object())) continue;
                            writer.storeTriple(label, d.subject(), d.predicate(), d.object(), evidence, title, chunkId);
                            entities.add(d.subject());
                            entities.add(d.object());
                            accepted++;
                            sectionAccepted++;
                        } else {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("subject", t.subject());
                            item.put("predicate", t.predicate());
                            item.put("object", t.object());
                            item.put("evidence", evidence);
                            item.put("section", title);
                            item.put("reason", d.reason());
                            rejected.add(item);
                            knowledge.send(emitter, "triple_rejected", item);
                        }
                    }
                    knowledge.send(emitter, "extract_progress", Map.of("section", title, "chunk", ci + 1,
                            "totalChunks", chunks.size(), "accepted", sectionAccepted));
                }
            }
            int embedded = knowledge.embedSectionArtifacts(label, title, List.copyOf(entities), chunkIds, chunks);
            knowledge.send(emitter, "section_done", Map.of("section", title, "index", si + 1,
                    "total", sections.size(), "triples", sectionAccepted, "embedded", embedded));
        }
        return accepted;
    }

    private Session knowledgeSession() {
        return knowledge.openSession();
    }
}
