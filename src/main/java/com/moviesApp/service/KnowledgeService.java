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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class KnowledgeService {

    private static final int    CHUNK_SIZE      = 1500;
    private static final int    CHUNK_OVERLAP   = 200;
    private static final int    TOP_K           = 5;
    private static final int    NEIGHBOR_LIMIT  = 10;
    private static final int    EMBED_BATCH     = 50;
    private static final String SOURCE_TYPE     = "knowledge_node";

    private final Driver                   driver;
    private final BikeEmbeddingRepository  repository;
    private final JinaEmbeddingProvider    jina;
    private final EntityExtractorService   extractor;
    private final OpenAiService            openAi;
    private final ObjectMapper             objectMapper;
    private final ExecutorService          executor = Executors.newCachedThreadPool();

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

    // ── Process: chunk → extract → Neo4j → embed → PG ───────────────────────

    public SseEmitter process(String text, String label) {
        SseEmitter emitter = new SseEmitter(300_000L);
        executor.submit(() -> {
            try {
                // 1. Chunk
                List<String> chunks = chunk(text);
                send(emitter, "chunk_done", Map.of("count", chunks.size()));

                // 2. Extract triples (deduplicated)
                Set<String> seenKeys = new LinkedHashSet<>();
                List<Triple> allTriples = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    List<Triple> triples = extractor.extract(chunks.get(i));
                    for (Triple t : triples) {
                        if (seenKeys.add(t.subject() + "|" + t.predicate() + "|" + t.object())) {
                            allTriples.add(t);
                        }
                    }
                    send(emitter, "extract_progress", Map.of(
                            "chunk", i + 1, "total", chunks.size(),
                            "chunkTriples", triples.size(), "totalTriples", allTriples.size()
                    ));
                }

                // 3. Store in Neo4j
                storeGraph(allTriples, label);
                long nodeCount = countNodes(label);
                send(emitter, "graph_done", Map.of(
                        "nodes", nodeCount, "relationships", allTriples.size(), "label", label
                ));

                // 4. Embed unique node names and store in PG
                List<String> nodeNames = getNodeNames(label);
                if (!nodeNames.isEmpty()) {
                    repository.deleteBySourceTypeAndLabels(SOURCE_TYPE, label);
                    int totalEmbedded = 0;
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
                        repository.saveAll(entities);
                        totalEmbedded += batch.size();
                        send(emitter, "embed_progress", Map.of(
                                "embedded", totalEmbedded, "total", nodeNames.size()
                        ));
                    }
                }

                send(emitter, "complete", Map.of(
                        "label", label, "nodes", nodeCount,
                        "relationships", allTriples.size(), "embedded", nodeNames.size()
                ));
                emitter.complete();

            } catch (Exception e) {
                try {
                    send(emitter, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    emitter.completeWithError(e);
                } catch (IOException ignored) {}
            }
        });
        return emitter;
    }

    // ── Chat: embed → PG similarity → graph expansion → OpenAI ──────────────

    public Map<String, Object> chat(String question, String label,
                                    List<Map<String, String>> history,
                                    double temperature, int maxTokens) throws Exception {
        float[] vec = jina.embed(List.of(question)).get(0);
        List<BikeEmbedding> matches = repository.findSimilarByLabel(
                floatArrayToVectorString(vec), label, TOP_K);

        List<Map<String, Object>> graphContext = buildGraphContext(matches, label);
        String contextText = buildContextString(graphContext, label);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                "You are a knowledgeable assistant. Use the following knowledge graph context to answer accurately. " +
                "If the answer is not in the context, say so honestly.\n\nContext:\n" + contextText));
        messages.addAll(history);

        String answer = openAi.chat(
                "gpt-4o-mini",
                temperature > 0 ? temperature : 0.7,
                maxTokens > 0 ? maxTokens : 800,
                messages
        );

        return Map.of(
                "answer",       answer,
                "label",        label,
                "graphContext", graphContext,
                "question",     question
        );
    }

    // ── Management ───────────────────────────────────────────────────────────

    public Map<String, Object> delete(String label) {
        try (Session session = driver.session()) {
            session.run("MATCH (n:KGNode {sourceLabel: $label}) DETACH DELETE n", Map.of("label", label));
        }
        repository.deleteBySourceTypeAndLabels(SOURCE_TYPE, label);
        return Map.of("deleted", label);
    }

    public Map<String, Object> status() {
        List<String> knownLabels = repository.findDistinctKnowledgeLabels();
        List<Map<String, Object>> perLabel = new ArrayList<>();
        for (String label : knownLabels) {
            long embeddings = repository.countBySourceTypeAndLabels(SOURCE_TYPE, label);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", label);
            entry.put("embeddings", embeddings);
            try (Session session = driver.session()) {
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

    // --- private helpers ---

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

    private void storeGraph(List<Triple> triples, String label) {
        String safeLabel = label.replaceAll("[^a-zA-Z0-9]", "_");
        try (Session session = driver.session()) {
            for (Triple t : triples) {
                String relType = t.predicate(); // already sanitized in EntityExtractorService
                // Use backtick-quoted dynamic label; relType is already [A-Z0-9_]
                String cypher = String.format(
                        "MERGE (a:KGNode:`%s` {name: $subject, sourceLabel: $label}) " +
                        "MERGE (b:KGNode:`%s` {name: $object,  sourceLabel: $label}) " +
                        "MERGE (a)-[:`%s` {predicate: $predicate}]->(b)",
                        safeLabel, safeLabel, relType
                );
                session.run(cypher, Map.of(
                        "subject", t.subject(), "object", t.object(),
                        "predicate", t.predicate(), "label", label
                ));
            }
        }
    }

    private long countNodes(String label) {
        try (Session session = driver.session()) {
            return session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) RETURN count(n) AS c", Map.of("l", label)
            ).single().get("c").asLong();
        }
    }

    private List<String> getNodeNames(String label) {
        try (Session session = driver.session()) {
            return session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) RETURN DISTINCT n.name AS name", Map.of("l", label)
            ).list(r -> r.get("name").asString());
        }
    }

    private List<Map<String, Object>> buildGraphContext(List<BikeEmbedding> matches, String label) {
        List<Map<String, Object>> context = new ArrayList<>();
        Set<String> expandedNodes = new HashSet<>();
        try (Session session = driver.session()) {
            for (BikeEmbedding match : matches) {
                if (!expandedNodes.add(match.getName())) continue;
                session.run(
                        "MATCH (n:KGNode {name: $name, sourceLabel: $label})-[r]-(nb:KGNode {sourceLabel: $label}) " +
                        "RETURN n.name AS node, type(r) AS relType, nb.name AS neighborName LIMIT $limit",
                        Map.of("name", match.getName(), "label", label, "limit", NEIGHBOR_LIMIT)
                ).list().forEach(r -> context.add(Map.of(
                        "node",         r.get("node").asString(),
                        "relationship", r.get("relType").asString(),
                        "neighbor",     r.get("neighborName").asString()
                )));
            }
        } catch (Exception e) {
            context.add(Map.of("error", "Graph expansion failed (Neo4j may be paused): " + e.getMessage()));
        }
        // Deduplicate by node|relationship|neighbor key
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
