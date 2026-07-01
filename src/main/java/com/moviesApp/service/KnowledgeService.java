package com.moviesApp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesApp.entities.BikeEmbedding;
import com.moviesApp.rag.EntityExtractorService;
import com.moviesApp.rag.EntityExtractorService.Triple;
import com.moviesApp.rag.JinaEmbeddingProvider;
import com.moviesApp.rag.OpenAiService;
import com.moviesApp.repositories.BikeEmbeddingRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private static final int    CHUNK_SIZE     = 1500;
    private static final int    CHUNK_OVERLAP  = 200;
    private static final int    TOP_K          = 5;
    private static final int    NEIGHBOR_LIMIT = 10;
    private static final int    EMBED_BATCH    = 50;
    private static final String SOURCE_TYPE    = "knowledge_node";

    private final Driver                  driver;
    private final BikeEmbeddingRepository repository;
    private final JinaEmbeddingProvider   jina;
    private final EntityExtractorService  extractor;
    private final OpenAiService           openAi;
    private final ObjectMapper            objectMapper;
    private final ExecutorService         executor = Executors.newCachedThreadPool();

    public KnowledgeService(
            @Qualifier("neo4jDriver2") Driver driver,
            BikeEmbeddingRepository repository,
            JinaEmbeddingProvider jina,
            EntityExtractorService extractor,
            OpenAiService openAi,
            ObjectMapper objectMapper) {
        this.driver       = driver;
        this.repository   = repository;
        this.jina         = jina;
        this.extractor    = extractor;
        this.openAi       = openAi;
        this.objectMapper = objectMapper;
    }

    // ── Suggest sections ─────────────────────────────────────────────────────

    private static final String SECTION_SYSTEM_PROMPT = """
            You are a text analyst. Split the provided text into logical thematic sections.
            Return ONLY a valid JSON array with this exact format:
            [{"title": "Section Title", "text": "The full text of this section..."}]
            Rules:
            - Each section covers one coherent topic or theme
            - Section titles are concise (2-5 words, title case)
            - ALL original text must appear across sections — do not paraphrase or omit anything
            - Return between 2 and 10 sections depending on text complexity
            - No markdown, no explanation — just the JSON array
            """;

    @Cacheable("suggestSections")
    public List<Map<String, String>> suggestSections(String text) throws Exception {
        String response = openAi.chat(SECTION_SYSTEM_PROMPT, text).strip();
        if (response.startsWith("```")) {
            response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, String>> sections = objectMapper.readValue(response,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        return sections.stream()
                .filter(s -> s.containsKey("title") && s.containsKey("text")
                        && !s.get("text").isBlank())
                .toList();
    }

    // ── Suggest graph ─────────────────────────────────────────────────────────

    private static final String GRAPH_DESIGN_PROMPT = """
            You are a knowledge graph designer. Analyze the text and design a structured knowledge graph.
            Return ONLY a valid JSON object — no markdown, no explanation:
            {
              "entities": [
                { "name": "Entity Name", "type": "Person|Event|Place|Organization|Concept" }
              ],
              "sections": [
                {
                  "title": "Section Title",
                  "forEntity": "Entity Name",
                  "sectionRelationship": "HAS_TOPIC_INFO",
                  "text": "The exact text content of this section..."
                }
              ],
              "entityRelationships": [
                { "from": "Entity A", "predicate": "RELATIONSHIP_TYPE", "to": "Entity B" }
              ]
            }
            Rules:
            - Entity names are exact proper nouns (1-4 words)
            - Entity type is one of: Person, Event, Place, Organization, Concept
            - Every section is assigned to exactly one entity via forEntity
            - sectionRelationship must follow HAS_<TOPIC>_INFO pattern in UPPERCASE_SNAKE_CASE
              (e.g. HAS_CAREER_INFO, HAS_GENERAL_INFO, HAS_DEATH_INFO, HAS_EDUCATION_INFO)
            - ALL original text must appear in sections — assign every sentence to exactly one section
            - Entity relationship predicates use UPPERCASE_SNAKE_CASE
              (e.g. RELATED_TO, PARTICIPATED_IN, LED, MARRIED_TO, ALLY_OF, ENEMY_OF)
            - Only create entityRelationships between entities that are explicitly connected in the text
            """;

    @Cacheable("suggestGraph")
    public Map<String, Object> suggestGraph(String text) throws Exception {
        String response = openAi.chat(GRAPH_DESIGN_PROMPT, text).strip();
        if (response.startsWith("```")) {
            response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = new LinkedHashMap<>(objectMapper.readValue(response, Map.class));
        // Normalize: LLMs sometimes return "relationships", "rels", or "edges" instead of the
        // contracted "entityRelationships". Rename whichever alias the model used.
        if (!result.containsKey("entityRelationships")) {
            for (String alias : List.of("relationships", "rels", "edges")) {
                if (result.containsKey(alias)) {
                    result.put("entityRelationships", result.remove(alias));
                    break;
                }
            }
        }
        return result;
    }

    // ── Process graph (structured) ────────────────────────────────────────────

    public SseEmitter processGraph(String label,
                                   List<Map<String, String>> entities,
                                   List<Map<String, String>> sections,
                                   List<Map<String, String>> entityRelationships) {
        SseEmitter emitter = new SseEmitter(300_000L);
        executor.submit(() -> {
            try {
                long existing = countNodes(label);
                if (existing > 0) {
                    log.warn("[already-exists guard] Neo4j already contains {} KGNode records with sourceLabel='{}' — skipping re-processing.", existing, label);
                    send(emitter, "already_exists", Map.of(
                            "label", label,
                            "nodes", existing,
                            "alreadyExistsCheckGuard", true,
                            "message", "Label already processed. DELETE /knowledge/" + label + " first to re-process."
                    ));
                    emitter.complete();
                    return;
                }

                // 1. Create entity nodes
                try (Session session = driver.session()) { // Neo4j
                    for (Map<String, String> entity : entities) {
                        createEntityNode(entity.get("name"), entity.getOrDefault("type", "Entity"), label, session);
                        send(emitter, "entity_stored", Map.of(
                                "name", entity.get("name"),
                                "type", entity.getOrDefault("type", "Entity")
                        ));
                    }
                }

                // 2. Per section: sub-node → relationship → chunk → extract → store → embed
                int totalSections = sections.size();
                Set<String> seenTripleKeys = new LinkedHashSet<>();
                int totalTriples = 0;

                for (int si = 0; si < totalSections; si++) {
                    Map<String, String> sec = sections.get(si);
                    String sectionTitle        = sec.getOrDefault("title", "section_" + (si + 1));
                    String forEntity           = sec.getOrDefault("forEntity", "");
                    String sectionRelationship = sec.getOrDefault("sectionRelationship", "HAS_INFO");
                    String sectionText         = sec.get("text");

                    send(emitter, "section_start", Map.of(
                            "section", sectionTitle, "forEntity", forEntity,
                            "index", si + 1, "total", totalSections
                    ));

                    // Create section sub-node + HAS_*_INFO relationship to entity
                    try (Session session = driver.session()) { // Neo4j
                        createSectionSubNode(sectionTitle, forEntity, sectionRelationship, label, session);
                    }

                    if (sectionText == null || sectionText.isBlank()) {
                        send(emitter, "section_done", Map.of(
                                "section", sectionTitle, "forEntity", forEntity,
                                "index", si + 1, "total", totalSections,
                                "triples", 0, "embedded", 0
                        ));
                        continue;
                    }

                    // Chunk
                    List<String> chunks = chunk(sectionText);
                    send(emitter, "chunk_done", Map.of("section", sectionTitle, "count", chunks.size()));

                    // Extract triples
                    List<Triple> sectionTriples = new ArrayList<>();
                    for (int ci = 0; ci < chunks.size(); ci++) {
                        List<Triple> triples = extractor.extract(chunks.get(ci));
                        for (Triple t : triples) {
                            if (seenTripleKeys.add(t.subject() + "|" + t.predicate() + "|" + t.object())) {
                                sectionTriples.add(t);
                            }
                        }
                        send(emitter, "extract_progress", Map.of(
                                "section", sectionTitle,
                                "chunk", ci + 1, "totalChunks", chunks.size(),
                                "chunkTriples", triples.size(),
                                "sectionTriples", sectionTriples.size()
                        ));
                    }

                    // Store triples (nodes tagged with sectionTitle)
                    storeGraph(sectionTriples, label, sectionTitle);
                    totalTriples += sectionTriples.size();
                    send(emitter, "graph_stored", Map.of(
                            "section", sectionTitle,
                            "triples", sectionTriples.size(),
                            "totalTriples", totalTriples
                    ));

                    // Embed section sub-node name + unique triple node names
                    List<String> toEmbed = Stream.concat(
                            Stream.of(sectionTitle),
                            uniqueNodeNames(sectionTriples).stream()
                    ).distinct().collect(Collectors.toList());

                    repository.deleteBySourceTypeAndLabelsAndNameIn(SOURCE_TYPE, label, toEmbed); // PostgreSQL
                    int embedded = 0;
                    for (int i = 0; i < toEmbed.size(); i += EMBED_BATCH) {
                        List<String> batch = toEmbed.subList(i, Math.min(i + EMBED_BATCH, toEmbed.size()));
                        List<float[]> vectors = jina.embed(batch);
                        List<BikeEmbedding> embedEntities = new ArrayList<>();
                        for (int j = 0; j < batch.size(); j++) {
                            String name = batch.get(j);
                            embedEntities.add(new BikeEmbedding(
                                    SOURCE_TYPE, label + "_" + Math.abs(name.hashCode()),
                                    name, label, name,
                                    floatArrayToVectorString(vectors.get(j)),
                                    "jina", vectors.get(j).length
                            ));
                        }
                        repository.saveAll(embedEntities); // PostgreSQL
                        embedded += batch.size();
                        send(emitter, "embed_progress", Map.of(
                                "section", sectionTitle,
                                "embedded", embedded, "total", toEmbed.size()
                        ));
                    }

                    send(emitter, "section_done", Map.of(
                            "section", sectionTitle, "forEntity", forEntity,
                            "index", si + 1, "total", totalSections,
                            "triples", sectionTriples.size(), "embedded", toEmbed.size()
                    ));
                }

                // 3. Entity → entity relationships
                int relsCreated = 0;
                try (Session session = driver.session()) { // Neo4j
                    relsCreated = createEntityRelationships(entityRelationships, label, session);
                }
                send(emitter, "relationships_stored", Map.of("count", relsCreated));

                // 4. Embed entity nodes themselves
                List<String> entityNames = entities.stream()
                        .map(e -> e.get("name"))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                if (!entityNames.isEmpty()) {
                    repository.deleteBySourceTypeAndLabelsAndNameIn(SOURCE_TYPE, label, entityNames); // PostgreSQL
                    List<float[]> vectors = jina.embed(entityNames);
                    List<BikeEmbedding> embedEntities = new ArrayList<>();
                    for (int i = 0; i < entityNames.size(); i++) {
                        String name = entityNames.get(i);
                        embedEntities.add(new BikeEmbedding(
                                SOURCE_TYPE, label + "_" + Math.abs(name.hashCode()),
                                name, label, name,
                                floatArrayToVectorString(vectors.get(i)),
                                "jina", vectors.get(i).length
                        ));
                    }
                    repository.saveAll(embedEntities); // PostgreSQL
                    send(emitter, "entities_embedded", Map.of("count", entityNames.size()));
                }

                long nodeCount = countNodes(label);
                send(emitter, "complete", Map.of(
                        "label", label,
                        "nodes", nodeCount,
                        "relationships", totalTriples + relsCreated,
                        "sections", totalSections,
                        "entities", entities.size()
                ));
                emitter.complete();

            } catch (Exception e) {
                try {
                    send(emitter, "error", Map.of("message",
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    emitter.completeWithError(e);
                } catch (IOException ignored) {}
            }
        });
        return emitter;
    }

    // ── Process ──────────────────────────────────────────────────────────────

    public SseEmitter process(List<Map<String, String>> sections, String label) {
        SseEmitter emitter = new SseEmitter(300_000L);
        executor.submit(() -> {
            try {
                int totalSections = sections.size();
                Set<String> seenTripleKeys = new LinkedHashSet<>();
                int totalTriples = 0;

                for (int si = 0; si < totalSections; si++) {
                    String sectionTitle = sections.get(si).getOrDefault("title", "section_" + (si + 1));
                    String sectionText  = sections.get(si).get("text");

                    if (sectionText == null || sectionText.isBlank()) {
                        send(emitter, "section_skip", Map.of(
                                "section", sectionTitle, "index", si + 1, "total", totalSections,
                                "reason", "empty text"
                        ));
                        continue;
                    }

                    send(emitter, "section_start", Map.of(
                            "section", sectionTitle, "index", si + 1, "total", totalSections
                    ));

                    // 1. Chunk
                    List<String> chunks = chunk(sectionText);
                    send(emitter, "chunk_done", Map.of(
                            "section", sectionTitle, "count", chunks.size()
                    ));

                    // 2. Extract triples for this section
                    List<Triple> sectionTriples = new ArrayList<>();
                    for (int ci = 0; ci < chunks.size(); ci++) {
                        List<Triple> triples = extractor.extract(chunks.get(ci));
                        for (Triple t : triples) {
                            if (seenTripleKeys.add(t.subject() + "|" + t.predicate() + "|" + t.object())) {
                                sectionTriples.add(t);
                            }
                        }
                        send(emitter, "extract_progress", Map.of(
                                "section", sectionTitle,
                                "chunk", ci + 1, "totalChunks", chunks.size(),
                                "chunkTriples", triples.size(),
                                "sectionTriples", sectionTriples.size()
                        ));
                    }

                    // 3. Store section triples in Neo4j with sectionTitle on nodes
                    storeGraph(sectionTriples, label, sectionTitle);
                    totalTriples += sectionTriples.size();
                    send(emitter, "graph_stored", Map.of(
                            "section", sectionTitle,
                            "triples", sectionTriples.size(),
                            "totalTriples", totalTriples
                    ));

                    // 4. Embed this section's unique node names and upsert into PG
                    List<String> nodeNames = uniqueNodeNames(sectionTriples);
                    if (!nodeNames.isEmpty()) {
                        repository.deleteBySourceTypeAndLabelsAndNameIn(SOURCE_TYPE, label, nodeNames); // PostgreSQL
                        int embedded = 0;
                        for (int i = 0; i < nodeNames.size(); i += EMBED_BATCH) {
                            List<String> batch = nodeNames.subList(i, Math.min(i + EMBED_BATCH, nodeNames.size()));
                            List<float[]> vectors = jina.embed(batch);
                            List<BikeEmbedding> entities = new ArrayList<>();
                            for (int j = 0; j < batch.size(); j++) {
                                String name = batch.get(j);
                                entities.add(new BikeEmbedding(
                                        SOURCE_TYPE, label + "_" + Math.abs(name.hashCode()),
                                        name, label, name,
                                        floatArrayToVectorString(vectors.get(j)),
                                        "jina", vectors.get(j).length
                                ));
                            }
                            repository.saveAll(entities); // PostgreSQL
                            embedded += batch.size();
                            send(emitter, "embed_progress", Map.of(
                                    "section", sectionTitle,
                                    "embedded", embedded, "total", nodeNames.size()
                            ));
                        }
                    }

                    send(emitter, "section_done", Map.of(
                            "section", sectionTitle, "index", si + 1, "total", totalSections,
                            "triples", sectionTriples.size(), "embedded", nodeNames.size()
                    ));
                }

                long nodeCount = countNodes(label);
                send(emitter, "complete", Map.of(
                        "label", label,
                        "nodes", nodeCount,
                        "relationships", totalTriples,
                        "sections", totalSections
                ));
                emitter.complete();

            } catch (Exception e) {
                try {
                    send(emitter, "error", Map.of("message",
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    emitter.completeWithError(e);
                } catch (IOException ignored) {}
            }
        });
        return emitter;
    }

    // ── Chat ─────────────────────────────────────────────────────────────────

    public Map<String, Object> chat(String question, String label,
                                    List<Map<String, String>> history,
                                    double temperature, int maxTokens,
                                    int topK, int neighborLimit) throws Exception {
        int effectiveTopK          = topK > 0          ? topK          : TOP_K;
        int effectiveNeighborLimit = neighborLimit > 0  ? neighborLimit : NEIGHBOR_LIMIT;

        // Embed the question into the same 1024-dim vector space Jina used during indexing.
        // Then run a cosine-distance search (pgvector <=> operator) against every stored node
        // embedding for this label — returns the TOP_K node names whose meaning is semantically
        // closest to the question. "Closest" = smallest angle between vectors in embedding space,
        // not keyword overlap. E.g. "who led the French forces?" matches "Napoleon" even if the
        // word "led" never appeared in the indexed text.
        float[] vec = jina.embed(List.of(question)).get(0);
        List<BikeEmbedding> matches = repository.findSimilarByLabel( // PostgreSQL
                floatArrayToVectorString(vec), label, effectiveTopK);

        List<Map<String, Object>> graphContext = buildGraphContext(matches, label, effectiveNeighborLimit);
        String contextText = buildContextString(graphContext, label);

        List<Map<String, String>> messages = new ArrayList<>(history);
        messages.add(0, Map.of("role", "system", "content",
                "You are a knowledgeable assistant. Use the following knowledge graph context to answer accurately. " +
                "If the answer is not in the context, say so honestly.\n\nContext:\n" + contextText));

        String answer = openAi.chat(
                "gpt-4o-mini",
                temperature > 0 ? temperature : 0.7,
                maxTokens > 0 ? maxTokens : 800,
                messages
        );

        List<String> seedNodes = matches.stream().map(BikeEmbedding::getName).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer",       answer);
        result.put("label",        label);
        result.put("question",     question);
        result.put("graphContext", graphContext);
        result.put("retrievalInfo", Map.of(
                "seedNodes",      seedNodes,
                "contextTriplets", graphContext.size(),
                "topK",           effectiveTopK,
                "neighborLimit",  effectiveNeighborLimit
        ));
        return result;
    }

    // ── Management ───────────────────────────────────────────────────────────

    public Map<String, Object> delete(String label) {
        try (Session session = driver.session()) { // Neo4j
            session.run("MATCH (n:KGNode {sourceLabel: $label}) DETACH DELETE n", Map.of("label", label));
        }
        repository.deleteBySourceTypeAndLabels(SOURCE_TYPE, label); // PostgreSQL
        return Map.of("deleted", label);
    }

    public Map<String, Object> status() {
        List<String> knownLabels = repository.findDistinctKnowledgeLabels(); // PostgreSQL
        List<Map<String, Object>> perLabel = new ArrayList<>();
        for (String label : knownLabels) {
            long embeddings = repository.countBySourceTypeAndLabels(SOURCE_TYPE, label); // PostgreSQL
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", label);
            entry.put("embeddings", embeddings);
            try (Session session = driver.session()) { // Neo4j
                long nodes = session.run(
                        "MATCH (n:KGNode {sourceLabel: $l}) RETURN count(n) AS c", Map.of("l", label)
                ).single().get("c").asLong();
                long rels = session.run(
                        "MATCH (:KGNode {sourceLabel: $l})-[r]->(:KGNode {sourceLabel: $l}) RETURN count(r) AS c",
                        Map.of("l", label)
                ).single().get("c").asLong();
                entry.put("nodes", nodes);
                entry.put("relationships", rels);
            } catch (Exception e) {
                entry.put("neo4jError", e.getMessage());
            }
            perLabel.add(entry);
        }
        return Map.of("knowledgeBases", perLabel, "total", knownLabels.size());
    }

    public List<String> labels() {
        return repository.findDistinctKnowledgeLabels();
    }

    // ── Chunking ──────────────────────────────────────────────────────────────

    private List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + CHUNK_SIZE, len);
            if (end < len) {
                int dot = text.lastIndexOf('.', end);
                if (dot > start + CHUNK_SIZE / 2) end = dot + 1;
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }
        return chunks;
    }

    // ── Neo4j writers ─────────────────────────────────────────────────────────

    private void storeGraph(List<Triple> triples, String label, String sectionTitle) {
        String safeLabel = label.replaceAll("[^a-zA-Z0-9]", "_");
        try (Session session = driver.session()) { // Neo4j
            for (Triple t : triples) {
                String cypher = String.format(
                        "MERGE (a:KGNode:`%s` {name: $subject, sourceLabel: $label}) " +
                        "  ON CREATE SET a.sectionTitle = $sectionTitle " +
                        "MERGE (b:KGNode:`%s` {name: $object,  sourceLabel: $label}) " +
                        "  ON CREATE SET b.sectionTitle = $sectionTitle " +
                        "MERGE (a)-[:`%s` {predicate: $predicate}]->(b)",
                        safeLabel, safeLabel, t.predicate()
                );
                session.run(cypher, Map.of(
                        "subject", t.subject(), "object", t.object(),
                        "predicate", t.predicate(), "label", label,
                        "sectionTitle", sectionTitle
                ));
            }
        }
    }

    private List<String> uniqueNodeNames(List<Triple> triples) {
        Set<String> names = new LinkedHashSet<>();
        for (Triple t : triples) {
            names.add(t.subject());
            names.add(t.object());
        }
        return new ArrayList<>(names);
    }

    private void createEntityNode(String name, String type, String label, Session session) {
        String safeLabel = label.replaceAll("[^a-zA-Z0-9]", "_");
        String safeType  = type.replaceAll("[^a-zA-Z0-9]", "_");
        session.run(String.format( // Neo4j
                "MERGE (e:KGNode:`%s`:`%s` {name: $name, sourceLabel: $label}) " +
                "ON CREATE SET e.nodeType = 'entity'",
                safeLabel, safeType),
                Map.of("name", name, "label", label));
    }

    private void createSectionSubNode(String sectionTitle, String forEntity,
                                      String sectionRelationship, String label, Session session) {
        String safeLabel = label.replaceAll("[^a-zA-Z0-9]", "_");
        String safeRel   = sectionRelationship.toUpperCase().replaceAll("[^A-Z0-9]", "_").replaceAll("_{2,}", "_");
        session.run(String.format( // Neo4j
                "MERGE (s:KGNode:`%s`:Section {name: $title, sourceLabel: $label}) " +
                "ON CREATE SET s.nodeType = 'section', s.forEntity = $forEntity",
                safeLabel),
                Map.of("title", sectionTitle, "forEntity", forEntity, "label", label));
        session.run(String.format( // Neo4j
                "MATCH (e:KGNode {name: $entity, sourceLabel: $label}) " +
                "MATCH (s:KGNode {name: $title,  sourceLabel: $label}) " +
                "MERGE (e)-[:`%s`]->(s)",
                safeRel),
                Map.of("entity", forEntity, "title", sectionTitle, "label", label));
    }

    private int createEntityRelationships(List<Map<String, String>> rels, String label, Session session) {
        int count = 0;
        for (Map<String, String> rel : rels) {
            String from = rel.get("from");
            String to   = rel.get("to");
            String pred = rel.get("predicate");
            if (from == null || to == null || pred == null) continue;
            String safePred = pred.toUpperCase().replaceAll("[^A-Z0-9]", "_").replaceAll("_{2,}", "_");
            session.run(String.format( // Neo4j
                    "MATCH (a:KGNode {name: $from, sourceLabel: $label}) " +
                    "MATCH (b:KGNode {name: $to,   sourceLabel: $label}) " +
                    "MERGE (a)-[:`%s`]->(b)",
                    safePred),
                    Map.of("from", from, "to", to, "label", label));
            count++;
        }
        return count;
    }

    // ── Neo4j queries ─────────────────────────────────────────────────────────

    private long countNodes(String label) {
        try (Session session = driver.session()) { // Neo4j
            return session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) RETURN count(n) AS c", Map.of("l", label)
            ).single().get("c").asLong();
        }
    }

    // ── Context builders ──────────────────────────────────────────────────────

    private List<Map<String, Object>> buildGraphContext(List<BikeEmbedding> matches, String label, int neighborLimit) {
        List<Map<String, Object>> context = new ArrayList<>();
        Set<String> expandedNodes = new HashSet<>();
        try (Session session = driver.session()) { // Neo4j
            for (BikeEmbedding match : matches) {
                if (!expandedNodes.add(match.getName())) continue;
                session.run(
                        "MATCH (n:KGNode {name: $name, sourceLabel: $label})-[r]-(nb:KGNode {sourceLabel: $label}) " +
                        "RETURN n.name AS node, type(r) AS relType, nb.name AS neighborName LIMIT $limit",
                        Map.of("name", match.getName(), "label", label, "limit", neighborLimit)
                ).list().forEach(r -> context.add(Map.of(
                        "node",         r.get("node").asString(""),
                        "relationship", r.get("relType").asString(""),
                        "neighbor",     r.get("neighborName").asString("")
                )));
            }
        } catch (Exception e) {
            context.add(Map.of("error",
                    "Graph expansion failed (Neo4j may be paused): " + e.getMessage()));
        }
        Set<String> seen = new LinkedHashSet<>();
        return context.stream()
                .filter(e -> seen.add(
                        e.getOrDefault("node", "") + "|" +
                        e.getOrDefault("relationship", "") + "|" +
                        e.getOrDefault("neighbor", "")))
                .collect(Collectors.toList());
    }

    private String buildContextString(List<Map<String, Object>> context, String label) {
        if (context.isEmpty()) return "No relevant knowledge graph context found for: " + label;
        StringBuilder sb = new StringBuilder("Knowledge graph context (").append(label).append("):\n");
        for (Map<String, Object> e : context) {
            if (e.containsKey("neighbor")) {
                sb.append(String.format("- %s %s %s\n",
                        e.get("node"), e.get("relationship"), e.get("neighbor")));
            }
        }
        return sb.toString();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
    }

    public static String floatArrayToVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        return sb.append("]").toString();
    }
}
