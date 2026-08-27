package com.moviesApp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesApp.entities.BikeEmbedding;
import com.moviesApp.entities.Relation;
import com.moviesApp.rag.EntityExtractorService;
import com.moviesApp.rag.EntityExtractorService.ExtractionResult;
import com.moviesApp.rag.EntityExtractorService.Triple;
import com.moviesApp.rag.JinaEmbeddingProvider;
import com.moviesApp.rag.OpenAiService;
import com.moviesApp.repositories.BikeEmbeddingRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
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
    private static final int    TOP_K          = 5;
    private static final int    NEIGHBOR_LIMIT = 10;
    private static final int    EMBED_BATCH    = 50;
    private static final String SOURCE_TYPE       = "knowledge_node";
    private static final String CHUNK_SOURCE_TYPE = "chunk_text";

    private static final Set<String> KNOWLEDGE_CACHES = Set.of("suggestGraph", "suggestSections");

    private final Driver                  driver;
    private final BikeEmbeddingRepository repository;
    private final JinaEmbeddingProvider   jina;
    private final EntityExtractorService  extractor;
    private final OpenAiService           openAi;
    private final ObjectMapper            objectMapper;
    private final CacheManager            cacheManager;
    private final ExecutorService         executor = Executors.newCachedThreadPool();

    public KnowledgeService(
            @Qualifier("neo4jDriver2") Driver driver,
            BikeEmbeddingRepository repository,
            JinaEmbeddingProvider jina,
            EntityExtractorService extractor,
            OpenAiService openAi,
            ObjectMapper objectMapper,
            CacheManager cacheManager) {
        this.driver       = driver;
        this.repository   = repository;
        this.jina         = jina;
        this.extractor    = extractor;
        this.openAi       = openAi;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
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
        String response = openAi.chatExtract(SECTION_SYSTEM_PROMPT, text, 16000).strip();
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
            You are a knowledge graph designer. Extract a COMPREHENSIVE knowledge graph from the provided text.
            Return ONLY a valid JSON object — no markdown, no explanation:
            {
              "entities": [
                { "name": "Entity Name", "type": "Person|Event|Place|Organization|Concept|Reason|Consequence" }
              ],
              "sections": [
                {
                  "title": "Section Title",
                  "forEntity": "Entity Name",
                  "sectionRelationship": "HAS_<TOPIC>_INFO",
                  "text": "Verbatim sentences from the source text..."
                }
              ],
              "entityRelationships": [
                { "from": "Entity A", "predicate": "RELATIONSHIP_TYPE", "to": "Entity B" }
              ]
            }

            ENTITIES — extract every named entity:
            - Include EVERY named person, place, event, organization, key concept, cause, and outcome in the text
            - Use the exact name as it appears in the text (1-5 words)
            - Types:
                Person       — named individual (Napoleon, Wellington, Anna, Tom)
                Event        — a specific, dated/scheduled happening — something that occurs, not a bare
                               time or day value (Battle of Waterloo, Congress of Vienna). A clock time or
                               a day-of-week alone is never an Event — see Concept below.
                Place        — any named physical location or venue, including businesses and venues
                               someone visits or stays at (Brussels, Hougoumont, Hotel Atlas, Bavarian
                               Bistro, Deutsches Museum, Munich Hbf). A hotel, restaurant, museum, or
                               station is a Place, not an Organization — you go there, it's a location.
                Organization — a named group, institution, or body that acts collectively, not a venue
                               (Prussian Army, Seventh Coalition, a company as an institution). If the
                               entity is somewhere you can physically visit, it's a Place instead.
                Concept      — abstract idea, period, preference, constraint, goal, or a standalone
                               time/date/day value that isn't itself a discrete happening (Pax Britannica,
                               Hundred Days, vegetarian options, easy plan, Saturday, 10:20). Bare clock
                               times and day names always go here, never under Event or Organization.
                Reason       — a cause or motivation behind an event (Napoleon's Return, Coalition Mobilization)
                Consequence  — an outcome or result of an event (Napoleon's Abdication, End of French Empire)

              Common mistakes to avoid:
                - Hotel / restaurant / museum / station names → Place, never Organization.
                - A clock time (10:20) or day-of-week/date (Saturday) → Concept, never Event or Organization.
                - A preference, requirement, or goal (vegetarian options, easy plan, cheapest option) → Concept.
                - Never fold a composite fact into one long, text-like entity name (e.g. "train arriving at
                  10:20" or "return train leaving at 18:40"). Split it instead: create a dedicated Event
                  entity for the booking/leg/appointment itself (e.g. "Outbound Train", "Return Train"), and
                  a separate Concept entity for the bare time (10:20, 18:40), then connect the two with a
                  specific relationship (see ARRIVES_AT / DEPARTS_AT below). A relationship target should
                  read like a name, never like a sentence fragment.

            SECTIONS — partition the ENTIRE source text, sentence by sentence:
            - Think of this as a PARTITION operation, not an extraction. Every single sentence
              from the source must land in exactly one section — none may be skipped or omitted.
            - Copy each sentence VERBATIM — never paraphrase, summarize, or shorten.
            - Work through the source top-to-bottom and assign each sentence to the most
              relevant section. A section with only 1–2 sentences is a sign you skipped something.
            - Aim for sections of 5–10+ sentences. Merge thin topics rather than creating stubs.
            - Use specific sectionRelationship types in UPPERCASE_SNAKE_CASE, for example:
              HAS_BACKGROUND_INFO, HAS_MILITARY_INFO, HAS_OUTCOME_INFO, HAS_LOCATION_INFO,
              HAS_ROLE_INFO, HAS_CAUSE_INFO, HAS_COMPOSITION_INFO, HAS_TIMELINE_INFO
              Prefer specific types over the generic HAS_TOPIC_INFO

            ENTITY RELATIONSHIPS — be exhaustive:
            - Extract EVERY connection between entities mentioned in the text
            - Causal/reason: CAUSED, LED_TO, RESULTED_IN, TRIGGERED, PREVENTED, REASON_FOR
              (ENABLED/ENABLES and other reasoned-benefit predicates are always POSSIBLE_-prefixed — see
              DEEP ANALYSIS below, never used bare here)
            - Temporal: PRECEDED, FOLLOWED
            - Participation: PARTICIPATED_IN, COMMANDED, DEFEATED, FOUGHT_AGAINST
            - Alliance/opposition: ALLIED_WITH, ENEMY_OF, OPPOSED
            - Structural: PART_OF, LOCATED_IN, MEMBER_OF
            - Location/distance: LOCATED_NEAR, LOCATED_FAR_FROM
            - Preference/requirement: WANTS, PREFERS, HAS_DIETARY_REQUIREMENT, HAS_DIETARY_OPTIONS, REQUIRES
            - Logistics/scheduling: TRAVELS_WITH, PLANS_TRIP_TO, BOOKED, OPEN_ON, CLOSED_ON, ARRIVES_AT,
              DEPARTS_AT, SCHEDULED_FOR
            - General: RELATED_TO
            - Prefer the most specific predicate that fits (e.g. HAS_DIETARY_REQUIREMENT over RELATED_TO
              for "Tom is vegetarian") — these lists are a starting vocabulary, not exhaustive; introduce a
              new well-named UPPERCASE_SNAKE_CASE predicate when nothing above fits the relationship
            - BOOKED (and similar logistics predicates) must point at a dedicated entity (the booking/leg
              itself, e.g. "Outbound Train"), never at a composite phrase — attach the time to that entity
              separately via ARRIVES_AT / DEPARTS_AT / SCHEDULED_FOR, per the ENTITIES rule above
            - Use these predicates as-is for connections directly stated or unambiguously implied by the
              source text — no invented connections at this stage
            - Aim for maximum coverage: every entity pair that interacts should have at least one relationship

            DEEP ANALYSIS — go beyond what the text says outright:
            - After extracting the direct relationships above, re-read the text as an analyst and reason
              about causal chains, motivations, and outcomes the text implies but never states plainly —
              e.g. an underlying motive behind a decision, a downstream effect the text doesn't spell out,
              or a connection between two events/entities that only becomes visible when you consider the
              text as a whole.
            - Express each such reasoned connection as a normal entityRelationships entry — same
              "from"/"predicate"/"to" shape — but PREFIX the predicate with "POSSIBLE_", e.g.
              POSSIBLE_CAUSED, POSSIBLE_LED_TO, POSSIBLE_REASON_FOR, POSSIBLE_RESULTED_IN. This is how an
              inferred connection is distinguished from one the source text actually states — never emit a
              bare (non-prefixed) predicate for something you reasoned into existence rather than read.
            - Do not fabricate connections between unrelated entities — every POSSIBLE_ relationship must be
              a reasonable, defensible reading of the text, not a guess.
            - Do not add a POSSIBLE_ duplicate of a relationship already captured as a direct one.
            - Reasoned-benefit predicates ALWAYS take the POSSIBLE_ prefix — this is not optional: ENABLES,
              ALLOWS, MAKES_POSSIBLE, JUSTIFIES (and similar) express a judgment about why a choice is good
              or what it makes achievable. Even when the underlying facts are stated in an explicit
              conditional sentence ("If they choose X, they can do Y"), turning that into "X ENABLES Y" is
              you synthesizing a causal/evaluative claim, not quoting one — so it is always
              POSSIBLE_ENABLES, POSSIBLE_ALLOWS, etc., never a bare ENABLES.
            """;

    @Cacheable("suggestGraph")
    public Map<String, Object> suggestGraph(String text) throws Exception {
        String response = openAi.chatDesign(GRAPH_DESIGN_PROMPT, text, 16000).strip();
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

    // ── Taxonomy (deep analysis, opt-in) ────────────────────────────────────────

    private static final String TAXONOMY_PROMPT = """
            You are a taxonomy analyst. Given a list of entity names extracted from a knowledge
            graph, identify every PARENT-CHILD (hierarchical, taxonomic) relationship among them —
            broader/narrower category, whole/part, type/instance, or "is a kind of" structure.

            Try hard: consider every pair, not just the obviously named ones. A hierarchy can be
            implicit — e.g. a specific named battle is a child of a broader campaign or war, a city
            is a child of the country/region it's in, a specific model is a child of its product
            line, an individual is a child of a group/organization they belong to, a sub-topic is a
            child of its parent topic.

            Return ONLY a valid JSON object — no markdown, no explanation:
            {
              "taxonomy": [
                { "parent": "Broader Entity Name", "child": "Narrower Entity Name" }
              ]
            }

            Rules:
            - Both "parent" and "child" MUST be entity names taken verbatim from the provided list
              — never invent a name that isn't in the list.
            - Only include a pair when the hierarchical relationship is a reasonable, defensible
              reading given the entity names and their apparent domain — do not force one that
              isn't there.
            - Each entity may have at most one direct parent in your output (pick the most
              specific, immediate parent, not a distant ancestor) — do not create cycles.
            - If no taxonomy exists among these entities at all, return {"taxonomy": []}.
            """;

    /**
     * Opt-in extra pass: feeds every already-extracted entity name (excluding structural
     * Section/Chunk nodes) to the LLM and asks specifically for parent-child structure, then
     * stores each validated pair as a dedicated (parent)-[:PARENT_OF]->(child) edge — distinct
     * from the general dynamic-predicate relationships the main extraction pass already writes.
     * LLM output is validated against the known entity set before writing, so a hallucinated
     * name never creates a stray node.
     */
    public Map<String, Object> detectAndStoreTaxonomy(String label) throws Exception {
        List<String> entityNames;
        try (Session session = driver.session()) { // Neo4j
            entityNames = session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) WHERE NOT n:Section AND NOT n:Chunk " +
                            "RETURN n.name AS name",
                    Map.of("l", label)
            ).list(r -> r.get("name").asString());
        }

        if (entityNames.size() < 2) {
            return Map.of("count", 0, "pairs", List.of());
        }

        String entityListText = entityNames.stream().map(n -> "- " + n).collect(Collectors.joining("\n"));
        String response = openAi.chatDesign(TAXONOMY_PROMPT, entityListText, 4000).strip();
        if (response.startsWith("```")) {
            response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> taxonomy = (List<Map<String, String>>) parsed.getOrDefault("taxonomy", List.of());

        Set<String> knownNames = new HashSet<>(entityNames);
        List<Map<String, String>> validPairs = new ArrayList<>();
        try (Session session = driver.session()) { // Neo4j
            for (Map<String, String> pair : taxonomy) {
                String parent = pair.get("parent");
                String child  = pair.get("child");
                if (parent == null || child == null) continue;
                if (!knownNames.contains(parent) || !knownNames.contains(child)) continue; // drop hallucinated names
                if (parent.equals(child)) continue;
                session.run(
                        "MATCH (p:KGNode {name: $parent, sourceLabel: $label}) " +
                                "MATCH (c:KGNode {name: $child, sourceLabel: $label}) " +
                                "MERGE (p)-[:PARENT_OF]->(c)",
                        Map.of("parent", parent, "child", child, "label", label)
                );
                validPairs.add(Map.of("parent", parent, "child", child));
            }
        }

        return Map.of("count", validPairs.size(), "pairs", validPairs);
    }

    /** Parent-child pairs only, for the FE's collapsible hierarchy tree view. */
    public List<Relation> hierarchy(String label) {
        try (Session session = driver.session()) { // Neo4j
            return session.run(
                    "MATCH (p:KGNode {sourceLabel: $l})-[:PARENT_OF]->(c:KGNode {sourceLabel: $l}) " +
                            "RETURN p.name AS parent, c.name AS child",
                    Map.of("l", label)
            ).list(r -> new Relation(r.get("parent").asString(), r.get("child").asString()));
        }
    }

    // ── Process graph (structured) ────────────────────────────────────────────

    public SseEmitter processGraph(String label,
                                   List<Map<String, String>> entities,
                                   List<Map<String, String>> sections,
                                   List<Map<String, String>> entityRelationships,
                                   boolean deepAnalysis) {
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

                List<String> canonicalNames = entities.stream()
                        .map(e -> e.get("name"))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

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
                        createSectionSubNode(sectionTitle, forEntity, sectionRelationship, label, sectionText, session);
                    }

                    if (sectionText == null || sectionText.isBlank()) {
                        send(emitter, "section_done", Map.of(
                                "section", sectionTitle, "forEntity", forEntity,
                                "index", si + 1, "total", totalSections,
                                "triples", 0, "embedded", 0
                        ));
                        continue;
                    }

                    // Chunk (sentence-aware)
                    List<String> chunks = chunk(sectionText);
                    send(emitter, "chunk_done", Map.of("section", sectionTitle, "count", chunks.size()));

                    // Create Chunk nodes in Neo4j + extract triples/descriptions per chunk
                    List<Triple> sectionTriples = new ArrayList<>();
                    Map<String, String> sectionDescriptions = new HashMap<>();
                    List<String> chunkIds   = new ArrayList<>();
                    List<String> chunkTexts = new ArrayList<>();

                    try (Session session = driver.session()) { // Neo4j
                        for (int ci = 0; ci < chunks.size(); ci++) {
                            String chunkText = chunks.get(ci);
                            String chunkId   = storeChunkNode(chunkText, ci, sectionTitle, label, session);
                            chunkIds.add(chunkId);
                            chunkTexts.add(chunkText);

                            ExtractionResult extracted = extractor.extract(chunkText, canonicalNames);
                            for (Triple t : extracted.triples()) {
                                if (seenTripleKeys.add(t.subject() + "|" + t.predicate() + "|" + t.object())) {
                                    sectionTriples.add(t);
                                }
                            }
                            // First-write-wins: earlier chunks' descriptions take priority
                            extracted.descriptions().forEach(sectionDescriptions::putIfAbsent);

                            send(emitter, "extract_progress", Map.of(
                                    "section", sectionTitle,
                                    "chunk", ci + 1, "totalChunks", chunks.size(),
                                    "chunkTriples", extracted.triples().size(),
                                    "sectionTriples", sectionTriples.size()
                            ));
                        }
                    }

                    // Store triples + entity descriptions in Neo4j
                    storeGraph(sectionTriples, sectionDescriptions, label, sectionTitle);
                    totalTriples += sectionTriples.size();
                    send(emitter, "graph_stored", Map.of(
                            "section", sectionTitle,
                            "triples", sectionTriples.size(),
                            "totalTriples", totalTriples
                    ));

                    // Embed entity names (knowledge_node) — for graph-mode retrieval
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

                    // Embed chunk TEXT (chunk_text) — for semantic passage retrieval
                    if (!chunkTexts.isEmpty()) {
                        List<float[]> chunkVecs = jina.embed(chunkTexts);
                        List<BikeEmbedding> chunkEmbs = new ArrayList<>();
                        for (int ci = 0; ci < chunkIds.size(); ci++) {
                            chunkEmbs.add(new BikeEmbedding(
                                    CHUNK_SOURCE_TYPE,
                                    label + "_ck_" + Math.abs(chunkIds.get(ci).hashCode()),
                                    chunkIds.get(ci), label, chunkTexts.get(ci),
                                    floatArrayToVectorString(chunkVecs.get(ci)),
                                    "jina", chunkVecs.get(ci).length
                            ));
                        }
                        repository.saveAll(chunkEmbs); // PostgreSQL
                        embedded += chunkTexts.size();
                    }

                    send(emitter, "section_done", Map.of(
                            "section", sectionTitle, "forEntity", forEntity,
                            "index", si + 1, "total", totalSections,
                            "triples", sectionTriples.size(), "embedded", embedded
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

                // 5. Optional deep analysis: detect and store parent-child taxonomy
                if (deepAnalysis) {
                    send(emitter, "taxonomy_start", Map.of());
                    try {
                        Map<String, Object> taxonomyResult = detectAndStoreTaxonomy(label);
                        send(emitter, "taxonomy_stored", taxonomyResult);
                    } catch (Exception e) {
                        // Non-fatal -- the main graph is already built; taxonomy is a bonus step.
                        send(emitter, "taxonomy_error", Map.of(
                                "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    }
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

                    // 1. Sentence-aware chunking
                    List<String> chunks = chunk(sectionText);
                    send(emitter, "chunk_done", Map.of(
                            "section", sectionTitle, "count", chunks.size()
                    ));

                    // 2. Create a section node so chunks have a parent to link to
                    String safeLabelFlat = label.replaceAll("[^a-zA-Z0-9]", "_");
                    try (Session session = driver.session()) { // Neo4j
                        session.run(String.format(
                                "MERGE (s:KGNode:`%s`:Section {name: $title, sourceLabel: $label}) " +
                                "ON CREATE SET s.nodeType = 'section', s.text = $text",
                                safeLabelFlat),
                                Map.of("title", sectionTitle, "label", label,
                                       "text", sectionText.length() > 2000
                                               ? sectionText.substring(0, 2000) : sectionText));
                    }

                    // 3. Create Chunk nodes + extract triples/descriptions per chunk
                    List<Triple> sectionTriples = new ArrayList<>();
                    Map<String, String> sectionDescriptions = new HashMap<>();
                    List<String> chunkIds   = new ArrayList<>();
                    List<String> chunkTexts = new ArrayList<>();

                    try (Session session = driver.session()) { // Neo4j
                        for (int ci = 0; ci < chunks.size(); ci++) {
                            String chunkText = chunks.get(ci);
                            String chunkId   = storeChunkNode(chunkText, ci, sectionTitle, label, session);
                            chunkIds.add(chunkId);
                            chunkTexts.add(chunkText);

                            ExtractionResult extracted = extractor.extract(chunkText);
                            for (Triple t : extracted.triples()) {
                                if (seenTripleKeys.add(t.subject() + "|" + t.predicate() + "|" + t.object())) {
                                    sectionTriples.add(t);
                                }
                            }
                            extracted.descriptions().forEach(sectionDescriptions::putIfAbsent);

                            send(emitter, "extract_progress", Map.of(
                                    "section", sectionTitle,
                                    "chunk", ci + 1, "totalChunks", chunks.size(),
                                    "chunkTriples", extracted.triples().size(),
                                    "sectionTriples", sectionTriples.size()
                            ));
                        }
                    }

                    // 4. Store triples + descriptions in Neo4j
                    storeGraph(sectionTriples, sectionDescriptions, label, sectionTitle);
                    totalTriples += sectionTriples.size();
                    send(emitter, "graph_stored", Map.of(
                            "section", sectionTitle,
                            "triples", sectionTriples.size(),
                            "totalTriples", totalTriples
                    ));

                    // 5. Embed entity names (knowledge_node)
                    List<String> nodeNames = uniqueNodeNames(sectionTriples);
                    int embedded = 0;
                    if (!nodeNames.isEmpty()) {
                        repository.deleteBySourceTypeAndLabelsAndNameIn(SOURCE_TYPE, label, nodeNames); // PostgreSQL
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

                    // 6. Embed chunk TEXT (chunk_text) — semantic passage retrieval
                    if (!chunkTexts.isEmpty()) {
                        List<float[]> chunkVectors = jina.embed(chunkTexts);
                        List<BikeEmbedding> chunkEmbs = new ArrayList<>();
                        for (int ci = 0; ci < chunkIds.size(); ci++) {
                            chunkEmbs.add(new BikeEmbedding(
                                    CHUNK_SOURCE_TYPE,
                                    label + "_ck_" + Math.abs(chunkIds.get(ci).hashCode()),
                                    chunkIds.get(ci), label, chunkTexts.get(ci),
                                    floatArrayToVectorString(chunkVectors.get(ci)),
                                    "jina", chunkVectors.get(ci).length
                            ));
                        }
                        repository.saveAll(chunkEmbs); // PostgreSQL
                        embedded += chunkTexts.size();
                    }

                    send(emitter, "section_done", Map.of(
                            "section", sectionTitle, "index", si + 1, "total", totalSections,
                            "triples", sectionTriples.size(), "embedded", embedded
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
                                    int topK, int neighborLimit, String ragMode, boolean strict,
                                    String seedPriority) throws Exception {
        int effectiveTopK          = topK > 0         ? topK         : TOP_K;
        int effectiveNeighborLimit = neighborLimit > 0 ? neighborLimit : NEIGHBOR_LIMIT;

        List<Map<String, Object>> graphContext;
        List<String>              seedNodes;
        String                    contextText;

        if ("vector".equals(ragMode)) {
            // PostgreSQL only — embed question, search both entity names and chunk text
            float[] vec = jina.embed(List.of(question)).get(0);
            List<BikeEmbedding> matches = repository.findSimilarAllTypesByLabel( // PostgreSQL
                    floatArrayToVectorString(vec), label, effectiveTopK);
            seedNodes    = matches.stream().map(BikeEmbedding::getName).collect(Collectors.toList());
            graphContext = List.of();
            contextText  = buildVectorContextFromMatches(matches, label);

        } else if ("graph".equals(ragMode)) {
            // Neo4j only — keyword-match node names, expand relationships, skip PostgreSQL
            graphContext = buildKeywordGraphContext(question, label, effectiveTopK, effectiveNeighborLimit);
            seedNodes    = graphContext.stream()
                    .map(e -> (String) e.getOrDefault("node", ""))
                    .distinct().filter(s -> !s.isEmpty()).collect(Collectors.toList());
            contextText  = buildContextString(graphContext, label);

        } else {
            // combined: keyword graph seeds (Neo4j) + vector seeds — entity names + chunk passages (PostgreSQL)
            // seedPriority: "graph" (default) puts precise keyword matches first; "vector" puts semantic matches first
            float[] vec = jina.embed(List.of(question)).get(0);
            List<BikeEmbedding> matches = repository.findSimilarAllTypesByLabel( // PostgreSQL
                    floatArrayToVectorString(vec), label, effectiveTopK);
            List<Map<String, Object>> vectorCtx  = buildGraphContext(matches, label, effectiveNeighborLimit);
            List<Map<String, Object>> keywordCtx = buildKeywordGraphContext(question, label, effectiveTopK, effectiveNeighborLimit);

            boolean graphFirst = !"vector".equals(seedPriority);
            Stream<Map<String, Object>> first  = graphFirst ? keywordCtx.stream() : vectorCtx.stream();
            Stream<Map<String, Object>> second = graphFirst ? vectorCtx.stream()  : keywordCtx.stream();

            Set<String> seen = new LinkedHashSet<>();
            graphContext = Stream.concat(first, second)
                    .filter(e -> {
                        if (e.containsKey("passage"))
                            return seen.add("passage:" + e.getOrDefault("source", ""));
                        return seen.add(
                                e.getOrDefault("node", "") + "|" +
                                e.getOrDefault("relationship", "") + "|" +
                                e.getOrDefault("neighbor", ""));
                    })
                    .collect(Collectors.toList());

            Stream<String> firstSeeds  = graphFirst
                    ? keywordCtx.stream().map(e -> (String) e.getOrDefault("node", "")).filter(s -> !s.isEmpty())
                    : matches.stream().map(BikeEmbedding::getName);
            Stream<String> secondSeeds = graphFirst
                    ? matches.stream().map(BikeEmbedding::getName)
                    : keywordCtx.stream().map(e -> (String) e.getOrDefault("node", "")).filter(s -> !s.isEmpty());
            seedNodes = Stream.concat(firstSeeds, secondSeeds).distinct().collect(Collectors.toList());

            contextText = buildContextString(graphContext, label);
        }

        // Relationships whose predicate is prefixed POSSIBLE_ (e.g. POSSIBLE_CAUSED, POSSIBLE_REASON_FOR)
        // are inferred connections the extractor reasoned out, not sentences taken verbatim from the
        // source. Both modes are told how to handle that distinction — strict mode may state them but
        // must label them as inferred; default mode may also add its own reasoning under the same label.
        String possibleRelHandling =
                "Some relationships in the context have a predicate starting with 'POSSIBLE_' " +
                "(e.g. POSSIBLE_CAUSED, POSSIBLE_REASON_FOR). These are inferred connections, not " +
                "statements taken verbatim from the source text. When your answer relies on one of them, " +
                "present it separately from directly-stated facts and label it clearly, e.g. " +
                "'Possible reason (inferred, not explicitly stated): ...' or " +
                "'Possible consequence (inferred, not explicitly stated): ...'. Never present an inferred " +
                "connection as if the source text stated it outright.";

        // strict=true: model must answer only from context, no pre-training knowledge allowed.
        // strict=false (default): model may supplement with general knowledge when context is thin.
        String systemPrompt = strict
                ? "You are a retrieval system, not an assistant. " +
                  "Answer using ONLY the exact facts stated in the context below. " +
                  "Do not add background, explanation, or any information not explicitly present in the context. " +
                  "Do not say 'however' or 'historically' or reference any outside knowledge. " +
                  "If the answer cannot be found in the context, output exactly this and nothing else: " +
                  "'Not in knowledge base.' " + possibleRelHandling + "\n\nContext:\n" + contextText
                : "You are a knowledgeable assistant. Use the following knowledge graph context to answer accurately. " +
                  "If the answer is not in the context, say so honestly. " +
                  "You may also go beyond what's directly stated and reason out likely reasons, causes, or " +
                  "consequences that the context strongly implies but doesn't say outright — as long as you " +
                  "clearly label any such reasoning as a possible/inferred conclusion, separate from stated facts, " +
                  "the same way you would label a POSSIBLE_ relationship. " + possibleRelHandling +
                  "\n\nContext:\n" + contextText;

        double effectiveTemperature = temperature > 0 ? temperature : 0.7;

        List<Map<String, String>> messages = new ArrayList<>(history);
        messages.add(0, Map.of("role", "system", "content", systemPrompt));

        String answer = openAi.chatDesign(
                effectiveTemperature,
                maxTokens > 0 ? maxTokens : 800,
                messages
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer",       answer);
        result.put("label",        label);
        result.put("question",     question);
        result.put("graphContext", graphContext);
        result.put("retrievalInfo", Map.of(
                "mode",            ragMode,
                "seedPriority",    seedPriority,
                "strict",          strict,
                "temperature",     effectiveTemperature,
                "seedNodes",       seedNodes,
                "contextTriplets", graphContext.size(),
                "topK",            effectiveTopK,
                "neighborLimit",   effectiveNeighborLimit
        ));
        return result;
    }

    // ── Compare mode: judge two answers ─────────────────────────────────────────

    private static final String COMPARE_PROMPT = """
            You are an impartial judge comparing two candidate answers to the same question,
            produced by two different retrieval strategies over the same knowledge base. Decide
            which answer is better supported, more accurate, and more directly responsive to the
            question -- or whether they're genuinely equivalent.

            Return ONLY a valid JSON object -- no markdown, no explanation outside the JSON:
            {
              "verdict": "A" | "B" | "tie",
              "explanation": "..."
            }

            Judge on:
            - Faithfulness: does the answer avoid claims unsupported by its own retrieved context?
            - Completeness: does it actually address every part of the question?
            - Specificity: does it name concrete entities/relationships rather than vague
              generalities?
            - Directness: does it answer the question asked, not a nearby one?

            "explanation" must be 2-4 sentences, specific about WHY one answer wins (or why they
            tie) -- not a generic restatement of the judging criteria.
            """;

    /**
     * LLM-as-judge for the FE's "compare" rag mode, which sends the same question through two
     * retrieval strategies and gets back two separate answers. modeA/modeB are the caller's own
     * labels for each side (e.g. "vector"/"combined") -- the returned verdict is normalized back
     * to one of those two labels (or "tie") rather than the generic "A"/"B" the LLM reasons in,
     * so the FE can compare it directly against the ragMode already on each message.
     */
    public Map<String, Object> compareAnalyze(String question, String answerA, String modeA,
                                               String answerB, String modeB) throws Exception {
        String input = String.format("""
                QUESTION:
                %s

                ANSWER A (%s):
                %s

                ANSWER B (%s):
                %s
                """, question, modeA, answerA, modeB, answerB);

        String response = openAi.chatDesign(COMPARE_PROMPT, input, 2000).strip();
        if (response.startsWith("```")) {
            response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new LinkedHashMap<>(objectMapper.readValue(response, Map.class));
        Object verdict = parsed.get("verdict");
        if ("A".equals(verdict)) {
            parsed.put("verdict", modeA);
        } else if ("B".equals(verdict)) {
            parsed.put("verdict", modeB);
        } else {
            parsed.put("verdict", "tie");
        }
        return parsed;
    }

    // ── Management ───────────────────────────────────────────────────────────

    public Map<String, Object> delete(String label) {
        try (Session session = driver.session()) { // Neo4j
            session.run("MATCH (n:KGNode {sourceLabel: $label}) DETACH DELETE n", Map.of("label", label));
        }
        repository.deleteAllByLabels(label); // PostgreSQL — clears knowledge_node + chunk_text
        KNOWLEDGE_CACHES.forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
        return Map.of("deleted", label, "cachesCleared", KNOWLEDGE_CACHES);
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

    /**
     * Full node/edge graph for one knowledge base, for FE visualization. Not cached (unlike
     * suggestGraph/suggestSections, which cache expensive LLM calls) -- this is a cheap direct
     * Neo4j read and should always reflect the latest state after a build/delete.
     */
    public Map<String, Object> graph(String label) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        try (Session session = driver.session()) { // Neo4j
            session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) RETURN n.name AS name, labels(n) AS labels",
                    Map.of("l", label)
            ).forEachRemaining(record -> {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", record.get("name").asString());
                node.put("labels", record.get("labels").asList(Value::asString).stream()
                        .filter(l -> !l.equals("KGNode"))
                        .toList());
                nodes.add(node);
            });

            session.run(
                    "MATCH (a:KGNode {sourceLabel: $l})-[r]->(b:KGNode {sourceLabel: $l}) " +
                            "RETURN a.name AS source, b.name AS target, type(r) AS type",
                    Map.of("l", label)
            ).forEachRemaining(record -> {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("source", record.get("source").asString());
                edge.put("target", record.get("target").asString());
                edge.put("type", record.get("type").asString());
                edges.add(edge);
            });
        }
        return Map.of("nodes", nodes, "edges", edges);
    }

    // ── Chunking ──────────────────────────────────────────────────────────────

    private List<String> chunk(String text) {
        // Split on sentence boundaries so extraction never sees a half-sentence
        String[] sentences = text.split("(?<=[.!?])\\s+");
        List<String> chunks  = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<String> overlap  = new ArrayList<>(); // last 2 sentences carried into next chunk

        for (String raw : sentences) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            if (current.length() > 0 && current.length() + s.length() + 1 > CHUNK_SIZE) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
                for (String prev : overlap) current.append(prev).append(" ");
                overlap.clear();
            }
            current.append(s).append(" ");
            overlap.add(s);
            if (overlap.size() > 2) overlap.remove(0);
        }
        if (!current.isEmpty()) chunks.add(current.toString().trim());
        return chunks.isEmpty() ? List.of(text.trim()) : chunks;
    }

    // ── Neo4j writers ─────────────────────────────────────────────────────────

    private void storeGraph(List<Triple> triples, Map<String, String> descriptions,
                            String label, String sectionTitle) {
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
            // Store entity descriptions — first-write-wins so earlier chunks keep better descriptions
            for (Map.Entry<String, String> d : descriptions.entrySet()) {
                if (d.getValue() == null || d.getValue().isBlank()) continue;
                session.run(
                        "MATCH (n:KGNode {name: $name, sourceLabel: $label}) " +
                        "WHERE n.description IS NULL SET n.description = $desc",
                        Map.of("name", d.getKey(), "label", label, "desc", d.getValue()));
            }
        }
    }

    // Creates a Chunk node in Neo4j and links it to its parent section via HAS_CHUNK.
    // Returns the chunk's unique name so it can be used as the PostgreSQL embedding key.
    private String storeChunkNode(String chunkText, int chunkIdx, String sectionTitle,
                                  String label, Session session) {
        String safeLabel = label.replaceAll("[^a-zA-Z0-9]", "_");
        String chunkId   = sectionTitle + "::chunk::" + chunkIdx;
        session.run(String.format( // Neo4j
                "MERGE (c:KGNode:`%s`:Chunk {name: $chunkId, sourceLabel: $label}) " +
                "ON CREATE SET c.nodeType = 'chunk', c.text = $text, c.sectionTitle = $section",
                safeLabel),
                Map.of("chunkId", chunkId, "label", label, "text", chunkText, "section", sectionTitle));
        session.run( // Neo4j — link section → chunk
                "MATCH (s:KGNode {name: $section, sourceLabel: $label}) " +
                "MATCH (c:KGNode {name: $chunkId,  sourceLabel: $label}) " +
                "MERGE (s)-[:HAS_CHUNK]->(c)",
                Map.of("section", sectionTitle, "chunkId", chunkId, "label", label));
        return chunkId;
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
                                      String sectionRelationship, String label, String text, Session session) {
        String safeLabel = label.replaceAll("[^a-zA-Z0-9]", "_");
        String safeRel   = sectionRelationship.toUpperCase().replaceAll("[^A-Z0-9]", "_").replaceAll("_{2,}", "_");
        session.run(String.format( // Neo4j
                "MERGE (s:KGNode:`%s`:Section {name: $title, sourceLabel: $label}) " +
                "ON CREATE SET s.nodeType = 'section', s.forEntity = $forEntity, s.text = $text",
                safeLabel),
                Map.of("title", sectionTitle, "forEntity", forEntity, "label", label,
                       "text", text != null ? text : ""));
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

    // vector mode: format chunk passages and entity names from similarity search results
    private String buildVectorContextFromMatches(List<BikeEmbedding> matches, String label) {
        if (matches.isEmpty()) return "No semantically relevant content found for: " + label;
        StringBuilder sb = new StringBuilder();
        List<BikeEmbedding> chunks   = matches.stream()
                .filter(m -> CHUNK_SOURCE_TYPE.equals(m.getSourceType())).toList();
        List<BikeEmbedding> entities = matches.stream()
                .filter(m -> !CHUNK_SOURCE_TYPE.equals(m.getSourceType())).toList();
        if (!chunks.isEmpty()) {
            sb.append("=== RELEVANT TEXT PASSAGES ===\n");
            for (BikeEmbedding c : chunks) {
                sb.append("[").append(c.getName()).append("]\n")
                  .append(c.getTextContent()).append("\n\n");
            }
        }
        if (!entities.isEmpty()) {
            sb.append("=== RELEVANT KNOWLEDGE NODES ===\n");
            for (BikeEmbedding e : entities) sb.append("- ").append(e.getName()).append("\n");
        }
        return sb.toString();
    }

    // graph mode: keyword-match node names in Neo4j, expand 1-hop + 2-hop — no PostgreSQL
    private List<Map<String, Object>> buildKeywordGraphContext(String question, String label,
                                                               int topK, int neighborLimit) {
        List<String> keywords = Arrays.stream(question.toLowerCase().split("\\W+"))
                .filter(w -> w.length() > 3)
                .distinct()
                .collect(Collectors.toList());
        if (keywords.isEmpty()) return List.of();

        List<Map<String, Object>> context = new ArrayList<>();
        Set<String> expanded    = new LinkedHashSet<>();
        Set<String> twoHopSeeds = new LinkedHashSet<>();

        try (Session session = driver.session()) { // Neo4j
            // 1-hop from keyword-matched nodes
            session.run(
                    "MATCH (n:KGNode {sourceLabel: $label})-[r]-(nb:KGNode {sourceLabel: $label}) " +
                    "WHERE any(kw IN $keywords WHERE toLower(n.name) CONTAINS kw) " +
                    "RETURN n.name AS node, type(r) AS relType, nb.name AS neighborName, " +
                    "coalesce(nb.text, '') AS neighborText, coalesce(nb.description, '') AS neighborDesc " +
                    "ORDER BY CASE WHEN type(r) STARTS WITH 'HAS_' THEN 1 ELSE 0 END ASC " +
                    "LIMIT $limit",
                    Map.of("label", label, "keywords", keywords, "limit", topK * neighborLimit)
            ).list().forEach(r -> {
                String relType      = r.get("relType").asString("");
                String nodeName     = r.get("node").asString("");
                String neighborName = r.get("neighborName").asString("");
                context.add(Map.of(
                        "node",         nodeName,
                        "relationship", relType,
                        "neighbor",     neighborName,
                        "neighborText", r.get("neighborText").asString(""),
                        "neighborDesc", r.get("neighborDesc").asString("")
                ));
                expanded.add(nodeName);
                if (!relType.startsWith("HAS_") && !expanded.contains(neighborName))
                    twoHopSeeds.add(neighborName);
            });

            // 2-hop from entity neighbors
            int twoHopLimit = Math.max(3, neighborLimit / 4);
            for (String name : twoHopSeeds) {
                if (!expanded.add(name)) continue;
                session.run(
                        "MATCH (n:KGNode {name: $name, sourceLabel: $label})-[r]-(nb:KGNode {sourceLabel: $label}) " +
                        "WHERE NOT type(r) STARTS WITH 'HAS_' AND NOT type(r) = 'HAS_CHUNK' " +
                        "RETURN n.name AS node, type(r) AS relType, nb.name AS neighborName, " +
                        "'' AS neighborText, coalesce(nb.description, '') AS neighborDesc " +
                        "LIMIT $limit",
                        Map.of("name", name, "label", label, "limit", twoHopLimit)
                ).list().forEach(r -> context.add(Map.of(
                        "node",         r.get("node").asString(""),
                        "relationship", r.get("relType").asString(""),
                        "neighbor",     r.get("neighborName").asString(""),
                        "neighborText", "",
                        "neighborDesc", r.get("neighborDesc").asString("")
                )));
            }
        } catch (Exception e) {
            context.add(Map.of("error", "Graph keyword search failed: " + e.getMessage()));
        }

        Set<String> seen = new LinkedHashSet<>();
        return context.stream()
                .filter(e -> seen.add(
                        e.getOrDefault("node", "") + "|" +
                        e.getOrDefault("relationship", "") + "|" +
                        e.getOrDefault("neighbor", "")))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildGraphContext(List<BikeEmbedding> matches, String label, int neighborLimit) {
        List<Map<String, Object>> context = new ArrayList<>();
        Set<String> seedEntityNames = new LinkedHashSet<>();

        // Pass 1 — collect chunk passages + resolve parent entities for chunk seeds
        try (Session session = driver.session()) { // Neo4j
            for (BikeEmbedding match : matches) {
                if (CHUNK_SOURCE_TYPE.equals(match.getSourceType())) {
                    String text = match.getTextContent();
                    if (text != null && !text.isEmpty())
                        context.add(Map.of("passage", text, "source", match.getName()));
                    // Traverse chunk → section → entity to get a graph expansion seed
                    session.run(
                            "MATCH (c:KGNode {name: $name, sourceLabel: $label})" +
                            "<-[:HAS_CHUNK]-(s:KGNode)<-[r]-(e:KGNode {sourceLabel: $label}) " +
                            "WHERE NOT e.nodeType IN ['section','chunk'] " +
                            "RETURN e.name AS entityName LIMIT 1",
                            Map.of("name", match.getName(), "label", label)
                    ).list().forEach(r -> {
                        String en = r.get("entityName").asString("");
                        if (!en.isEmpty()) seedEntityNames.add(en);
                    });
                } else {
                    seedEntityNames.add(match.getName());
                }
            }
        } catch (Exception e) {
            log.warn("Chunk parent traversal failed: {}", e.getMessage());
        }

        // Pass 2 — 1-hop expand from seed entities, collect non-section neighbors for 2-hop
        Set<String> expanded = new HashSet<>(seedEntityNames);
        Set<String> twoHopSeeds = new LinkedHashSet<>();
        try (Session session = driver.session()) { // Neo4j
            for (String name : seedEntityNames) {
                session.run(
                        "MATCH (n:KGNode {name: $name, sourceLabel: $label})-[r]-(nb:KGNode {sourceLabel: $label}) " +
                        "RETURN n.name AS node, type(r) AS relType, nb.name AS neighborName, " +
                        "coalesce(nb.text, '') AS neighborText, coalesce(nb.description, '') AS neighborDesc " +
                        "ORDER BY CASE WHEN type(r) STARTS WITH 'HAS_' THEN 1 ELSE 0 END ASC " +
                        "LIMIT $limit",
                        Map.of("name", name, "label", label, "limit", neighborLimit)
                ).list().forEach(r -> {
                    String relType      = r.get("relType").asString("");
                    String neighborName = r.get("neighborName").asString("");
                    context.add(Map.of(
                            "node",         r.get("node").asString(""),
                            "relationship", relType,
                            "neighbor",     neighborName,
                            "neighborText", r.get("neighborText").asString(""),
                            "neighborDesc", r.get("neighborDesc").asString("")
                    ));
                    if (!relType.startsWith("HAS_") && !expanded.contains(neighborName))
                        twoHopSeeds.add(neighborName);
                });
            }
        } catch (Exception e) {
            context.add(Map.of("error", "Graph expansion failed: " + e.getMessage()));
        }

        // Pass 3 — 2-hop expand (entity-to-entity only, smaller limit)
        int twoHopLimit = Math.max(3, neighborLimit / 4);
        try (Session session = driver.session()) { // Neo4j
            for (String name : twoHopSeeds) {
                if (!expanded.add(name)) continue;
                session.run(
                        "MATCH (n:KGNode {name: $name, sourceLabel: $label})-[r]-(nb:KGNode {sourceLabel: $label}) " +
                        "WHERE NOT type(r) STARTS WITH 'HAS_' AND NOT type(r) = 'HAS_CHUNK' " +
                        "RETURN n.name AS node, type(r) AS relType, nb.name AS neighborName, " +
                        "'' AS neighborText, coalesce(nb.description, '') AS neighborDesc " +
                        "LIMIT $limit",
                        Map.of("name", name, "label", label, "limit", twoHopLimit)
                ).list().forEach(r -> context.add(Map.of(
                        "node",         r.get("node").asString(""),
                        "relationship", r.get("relType").asString(""),
                        "neighbor",     r.get("neighborName").asString(""),
                        "neighborText", "",
                        "neighborDesc", r.get("neighborDesc").asString("")
                )));
            }
        } catch (Exception e) {
            log.warn("2-hop expansion failed: {}", e.getMessage());
        }

        Set<String> seen = new LinkedHashSet<>();
        return context.stream()
                .filter(e -> {
                    if (e.containsKey("passage"))
                        return seen.add("passage:" + e.getOrDefault("source", ""));
                    return seen.add(
                            e.getOrDefault("node", "") + "|" +
                            e.getOrDefault("relationship", "") + "|" +
                            e.getOrDefault("neighbor", ""));
                })
                .collect(Collectors.toList());
    }

    private String buildContextString(List<Map<String, Object>> context, String label) {
        if (context.isEmpty()) return "No relevant knowledge graph context found for: " + label;

        List<Map<String, Object>> passages = context.stream().filter(e -> e.containsKey("passage")).toList();
        List<Map<String, Object>> triplets = context.stream().filter(e -> e.containsKey("neighbor")).toList();

        StringBuilder sb = new StringBuilder();

        if (!passages.isEmpty()) {
            sb.append("=== RELEVANT TEXT PASSAGES ===\n");
            for (Map<String, Object> p : passages) {
                sb.append("[").append(p.get("source")).append("]\n")
                  .append(p.get("passage")).append("\n\n");
            }
        }

        if (!triplets.isEmpty()) {
            sb.append("=== KNOWLEDGE GRAPH RELATIONSHIPS (").append(label).append(") ===\n");
            for (Map<String, Object> e : triplets) {
                String desc         = (String) e.getOrDefault("neighborDesc", "");
                String neighborText = (String) e.getOrDefault("neighborText", "");
                String neighbor     = String.valueOf(e.get("neighbor"));

                if (desc != null && !desc.isEmpty()) {
                    sb.append(String.format("- %s %s %s (%s)\n",
                            e.get("node"), e.get("relationship"), neighbor, desc));
                } else {
                    sb.append(String.format("- %s %s %s\n",
                            e.get("node"), e.get("relationship"), neighbor));
                }
                if (neighborText != null && !neighborText.isEmpty()) {
                    String snippet = neighborText.length() > 500
                            ? neighborText.substring(0, 500) + "..." : neighborText;
                    sb.append("  [").append(snippet).append("]\n");
                }
            }
        }

        return sb.isEmpty() ? "No relevant knowledge graph context found for: " + label : sb.toString();
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
