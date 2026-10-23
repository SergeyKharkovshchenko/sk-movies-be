package com.moviesApp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesApp.entities.BikeEmbedding;
import com.moviesApp.entities.Relation;
import com.moviesApp.rag.EntityExtractorService;
import com.moviesApp.rag.EvidenceCheck;
import com.moviesApp.rag.EntityExtractorService.ExtractionResult;
import com.moviesApp.rag.EntityExtractorService.Triple;
import com.moviesApp.rag.JinaEmbeddingProvider;
import com.moviesApp.rag.OpenAiService;
import com.moviesApp.repositories.BikeEmbeddingRepository;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Query;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private static final int    CHUNK_SIZE     = 1500;
    private static final int    TOP_K          = 5;
    private static final int    NEIGHBOR_LIMIT = 10;
    private static final int    DEFAULT_MAX_HOPS = 2;
    private static final int    MAX_HOPS_CAP     = 5;
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
    private final TriplyService           triply;
    private final ExecutorService         executor = Executors.newCachedThreadPool();

    public KnowledgeService(
            @Qualifier("neo4jDriver2") Driver driver,
            BikeEmbeddingRepository repository,
            JinaEmbeddingProvider jina,
            EntityExtractorService extractor,
            OpenAiService openAi,
            ObjectMapper objectMapper,
            CacheManager cacheManager,
            TriplyService triply) {
        this.driver       = driver;
        this.repository   = repository;
        this.jina         = jina;
        this.extractor    = extractor;
        this.openAi       = openAi;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
        this.triply       = triply;
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

            CLOSED WORLD — the text is the only source of truth:
            - Use ONLY what the text itself states. Never add anything from your own knowledge, even if
              it is true in the real world (e.g. other films an actor appeared in, a person's birthplace,
              a well-known fact about a famous entity).
            - Never infer, guess, or "complete" connections. If two entities both appear in the text but
              no sentence connects them, there is no relationship between them.
            - Every entity must be named in the text; every relationship must be stated by one sentence of it.

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
                { "from": "Entity A", "predicate": "RELATIONSHIP_TYPE", "to": "Entity B",
                  "evidence": "The exact sentence from the text that states this relationship." }
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

            ENTITY RELATIONSHIPS — every connection the text states, nothing more:
            - Extract every connection between entities that a sentence of the text states
            - "evidence" MUST be ONE sentence copied verbatim from the text that names both entities.
              No such sentence → no relationship. Relationships without valid evidence are discarded.
            - Causal/reason: CAUSED, LED_TO, RESULTED_IN, TRIGGERED, PREVENTED, REASON_FOR, ENABLED —
              only when the text states the cause or effect outright, never as your own reasoning
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
            - Only connections the text states directly — never implied, reasoned, or "likely" ones, and
              never one completed from what you know about these entities
            - Never use a POSSIBLE_ (or any other "inferred") predicate
            """;

    // Results are cached (PostgreSQL-backed, 24h) by this key. The prefix versions the cache: a
    // cached answer from before the closed-world rules may hold invented relationships, and must
    // not be served for the same text again.
    public static String suggestGraphCacheKey(String text) {
        return "closed-world-v1:" + text;
    }

    @Cacheable(value = "suggestGraph", key = "T(com.moviesApp.service.KnowledgeService).suggestGraphCacheKey(#text)")
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
        enforceClosedWorld(result, text);
        return result;
    }

    /**
     * Drops whatever the design LLM added beyond the text: entities the text never names, and
     * relationships whose quoted evidence sentence isn't in the text or doesn't name both entities
     * (plus any POSSIBLE_/inferred predicate). What was dropped is returned alongside, so the FE
     * can show it and nothing disappears silently.
     */
    private static void enforceClosedWorld(Map<String, Object> result, String text) {
        List<String> droppedEntities = new ArrayList<>();
        if (result.get("entities") instanceof List<?> entities) {
            List<Object> kept = new ArrayList<>();
            for (Object e : entities) {
                String name = e instanceof Map<?, ?> m && m.get("name") instanceof String s ? s : null;
                if (name != null && EvidenceCheck.occursIn(text, name)) kept.add(e);
                else if (name != null) droppedEntities.add(name);
            }
            result.put("entities", kept);
        }

        List<String> droppedRelationships = new ArrayList<>();
        if (result.get("entityRelationships") instanceof List<?> rels) {
            List<Object> kept = new ArrayList<>();
            for (Object r : rels) {
                if (!(r instanceof Map<?, ?> m)) continue;
                String from = String.valueOf(m.get("from")), to = String.valueOf(m.get("to"));
                String predicate = String.valueOf(m.get("predicate"));
                String evidence = m.get("evidence") instanceof String s ? s : null;
                String reason = predicate.toUpperCase(Locale.ROOT).startsWith("POSSIBLE")
                        ? "inferred predicate"
                        : EvidenceCheck.reject(text, evidence, from, to);
                if (reason == null) kept.add(r);
                else droppedRelationships.add(from + " -" + predicate + "-> " + to + " (" + reason + ")");
            }
            result.put("entityRelationships", kept);
        }

        if (!droppedEntities.isEmpty() || !droppedRelationships.isEmpty()) {
            log.info("[suggest-graph] closed world: dropped entities {} and relationships {}",
                    droppedEntities, droppedRelationships);
        }
        result.put("droppedEntities", droppedEntities);
        result.put("droppedRelationships", droppedRelationships);
    }

    // ── Taxonomy (deep analysis, opt-in) ────────────────────────────────────────

    private static final String TAXONOMY_PROMPT = """
            You are a taxonomy analyst. You get the SOURCE TEXT of a knowledge graph and the list of
            entity names extracted from it. Identify the PARENT-CHILD (hierarchical, taxonomic)
            relationships among those entities that the text itself states -- broader/narrower
            category, type/instance, whole/part, or "is a kind of" structure.

            CLOSED WORLD -- the text is the only source of truth:
            - Use ONLY hierarchy the text states. Never add anything from your own knowledge, even if
              it is true in the real world (e.g. a film's genre, a city's country, a person's group).
            - Do not infer or guess structure from names or domain. If no sentence of the text states
              that X is a kind / type / instance / part / member of Y, there is no pair.
            - A hierarchy is NOT any other relationship: "X directed Y", "X acted in Y", "X was released
              in 2010", "X lives in Y" are relationships, never parent-child pairs.

            Return ONLY a valid JSON object -- no markdown, no explanation:
            {
              "taxonomy": [
                {"parent": "Broader Entity", "child": "Narrower Entity",
                 "evidence": "The exact sentence from the text stating the child is a kind/part of the parent."}
              ]
            }

            Rules:
            - "parent" and "child" MUST be entity names taken verbatim from the provided list. The
              parent is the broader category or whole, the child the specific item or part: for
              "Inception is a Science Fiction movie", parent = Science Fiction, child = Inception.
            - "evidence" MUST be ONE sentence copied verbatim from the text that names both entities.
              Pairs without such a sentence are discarded.
            - Each entity may appear as a child at most once (its most specific, immediate parent);
              no cycles.
            - If the text states no hierarchy, return {"taxonomy": []}.
            """;

    // A sentence only supports a parent-child pair if it states a hierarchy, not just any link
    // between the two entities ("Inception is a Science Fiction movie" yes; "Tom Hardy acted in
    // Dunkirk", "... was released in 2010" no). The cue also fixes the direction, which the LLM
    // gets wrong often enough to matter: before a CHILD_FIRST cue stands the child ("Inception
    // is a <genre>"), before a PARENT_FIRST cue the parent ("Drama includes Little Women").
    private static final Pattern CHILD_FIRST_CUE = Pattern.compile(
            "\\b(is|are|was|were)\\s+(a|an|one of|some|kind|kinds|type|types|sort|part|member|members|example|subtype|category|genre)\\b" +
            "|\\b(kind|kinds|type|types|sort|sorts|class|subclass|category|categories|genre|part|member|members|subset|example|examples) of\\b" +
            "|\\bbelongs? to\\b");
    private static final Pattern PARENT_FIRST_CUE = Pattern.compile(
            "\\b(consists? of|comprises?|includes?|including|contains?|such as)\\b");

    /** Null when the proposed pair is backed by the text, otherwise why it isn't. */
    private static String validateTaxonomyPair(String a, String b, String evidence, String sourceText,
                                               Set<String> knownNames) {
        if (a == null || b == null || !knownNames.contains(a) || !knownNames.contains(b)) return "unknown entity";
        if (a.equals(b)) return "self-pair";
        String reason = EvidenceCheck.reject(sourceText, evidence, a, b);
        if (reason != null) return reason;
        return orientByEvidence(evidence, a, b) == null ? "evidence states no hierarchy" : null;
    }

    /**
     * {parent, child} as the evidence sentence states it, or null when the sentence states no
     * hierarchy between the two (no cue, or both entities on the same side of it).
     */
    private static String[] orientByEvidence(String evidence, String a, String b) {
        String ev = EvidenceCheck.normalized(evidence);
        int ia = EvidenceCheck.mentionIndex(evidence, a), ib = EvidenceCheck.mentionIndex(evidence, b);
        if (ia < 0 || ib < 0 || ia == ib) return null;
        for (boolean childFirst : new boolean[]{true, false}) {
            var m = (childFirst ? CHILD_FIRST_CUE : PARENT_FIRST_CUE).matcher(ev);
            while (m.find()) {
                int cue = m.start();
                String before = ia < cue && ib > cue ? a : ib < cue && ia > cue ? b : null;
                if (before == null) continue;
                String after = before.equals(a) ? b : a;
                return childFirst ? new String[]{after, before} : new String[]{before, after};
            }
        }
        return null;
    }

    /**
     * Opt-in extra pass: asks the LLM for the parent-child structure the source text states among
     * the already-extracted entity names (excluding structural Section/Chunk nodes), then stores
     * each validated pair as a dedicated (parent)-[:PARENT_OF]->(child) edge -- distinct from the
     * general dynamic-predicate relationships the main extraction pass already writes.
     *
     * Closed world, like extraction: each pair must quote one sentence of the source text that
     * names both entities AND states a hierarchy (CHILD_FIRST_CUE / PARENT_FIRST_CUE, which also set the
     * direction); unknown names, missing or
     * non-taxonomic evidence, self-pairs, a second parent for the same child and cycles are all
     * dropped before anything is written.
     */
    public Map<String, Object> detectAndStoreTaxonomy(String label, String sourceText) throws Exception {
        List<String> entityNames;
        try (Session session = driver.session()) { // Neo4j
            entityNames = session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) WHERE NOT n:Section AND NOT n:Chunk " +
                            "RETURN n.name AS name",
                    Map.of("l", label)
            ).list(r -> r.get("name").asString());
        }

        if (entityNames.size() < 2 || sourceText == null || sourceText.isBlank()) {
            return Map.of("count", 0, "pairs", List.of(), "dropped", List.of());
        }

        String input = "SOURCE TEXT:\n" + sourceText + "\n\nENTITY NAMES:\n" +
                entityNames.stream().map(n -> "- " + n).collect(Collectors.joining("\n"));
        String response = openAi.chatDesign(TAXONOMY_PROMPT, input, 16000).strip();
        if (response.startsWith("```")) {
            response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
        List<Map<String, Object>> proposals = asListOfMaps(parsed.get("taxonomy"));

        Set<String> knownNames = new HashSet<>(entityNames);
        Map<String, String> parentOf = new HashMap<>();
        List<Map<String, String>> validPairs = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (Map<String, Object> p : proposals) {
            String parent = p.get("parent") instanceof String s ? s.strip() : null;
            String child = p.get("child") instanceof String s ? s.strip() : null;
            String evidence = p.get("evidence") instanceof String s ? s : null;
            String reason = validateTaxonomyPair(parent, child, evidence, sourceText, knownNames);
            if (reason == null) {
                // Direction comes from the sentence, not from which field the LLM put a name in.
                String[] oriented = orientByEvidence(evidence, parent, child);
                parent = oriented[0];
                child = oriented[1];
                if (parentOf.containsKey(child)) reason = "child already has a parent";
                else if (isAncestor(child, parent, parentOf)) reason = "would create a cycle";
            }
            if (reason != null) {
                dropped.add(parent + " > " + child + " (" + reason + ")");
                continue;
            }
            parentOf.put(child, parent);
            validPairs.add(Map.of("parent", parent, "child", child));
        }
        if (!dropped.isEmpty()) log.info("[taxonomy] closed world: dropped {} pair(s): {}", dropped.size(), dropped);

        try (Session session = driver.session()) { // Neo4j
            for (Map<String, String> pair : validPairs) {
                session.run(
                        "MATCH (p:KGNode {name: $parent, sourceLabel: $label}) " +
                                "MATCH (c:KGNode {name: $child, sourceLabel: $label}) " +
                                "MERGE (p)-[:PARENT_OF]->(c)",
                        Map.of("parent", pair.get("parent"), "child", pair.get("child"), "label", label)
                );
            }
        }

        return Map.of("count", validPairs.size(), "pairs", validPairs, "dropped", dropped);
    }

    /**
     * Parent-child pairs only, for the FE's collapsible hierarchy tree view -- from the text flow's
     * taxonomy step (parent)-[:PARENT_OF]->(child) and from CSV hierarchy columns (child)-[:IS_A]->(parent).
     */
    public List<Relation> hierarchy(String label) {
        try (Session session = driver.session()) { // Neo4j
            return session.run(
                    "MATCH (p:KGNode {sourceLabel: $l})-[:PARENT_OF]->(c:KGNode {sourceLabel: $l}) " +
                            "RETURN p.name AS parent, c.name AS child " +
                            "UNION " +
                            "MATCH (c:KGNode {sourceLabel: $l})-[:IS_A]->(p:KGNode {sourceLabel: $l}) " +
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
        // 5 minutes wasn't enough for a knowledge base with 25+ sections -- each section does an
        // LLM extraction call plus embedding calls, so a large paste can legitimately run past
        // 300s and get cut off right before reaching the (sequentially last) taxonomy step below,
        // leaving a partially-processed graph with no parent-child data despite deepAnalysis=true.
        SseEmitter emitter = new SseEmitter(900_000L);
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
                                    "droppedTriples", extracted.dropped().size(),
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
                        // The sections are the source text (step 2 copies every sentence verbatim),
                        // so the taxonomy pass can be held to what they state.
                        String sourceText = sections.stream()
                                .map(s -> s.getOrDefault("text", ""))
                                .filter(t -> t != null && !t.isBlank())
                                .collect(Collectors.joining("\n"));
                        Map<String, Object> taxonomyResult = detectAndStoreTaxonomy(label, sourceText);
                        send(emitter, "taxonomy_stored", taxonomyResult);
                    } catch (Exception e) {
                        // Non-fatal -- the main graph is already built; taxonomy is a bonus step.
                        send(emitter, "taxonomy_error", Map.of(
                                "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    }
                }

                triply.syncGraphAsync(label); // TriplyDB mirror -- async, failures only logged
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

    // ── Import CSV (deterministic, non-LLM) ─────────────────────────────────────
    //
    // Neo4j's own LOAD CSV reads from the server's local import directory or a URL, neither of
    // which fits a browser drag-and-drop upload through this API -- so instead we parse the CSV
    // in-process and drive the same Bolt session the rest of this service already uses, via
    // UNWIND-free per-row MERGE calls (small row counts here, so per-row calls stay simple and
    // readable rather than building dynamic batched Cypher).
    //
    // Classification is by column-naming convention alone (OMG/Spider-style "<Table>_Identifier"
    // foreign keys), not a fixed schema, so it generalizes to any CSV set that follows the same
    // convention:
    //   - ENTITY: a column exactly named "<table>_Identifier" exists -> that's the node's key;
    //             every other "*_Identifier" column becomes an outgoing relationship.
    //   - JOIN:   no own-PK column, but 2+ "*_Identifier" columns -> a relationship between the
    //             first two, with remaining columns as relationship properties.
    //   - TAG:    no own-PK column, exactly 1 "*_Identifier" column -> adds this table's name as
    //             an extra label (plus any extra columns as properties) onto the node that column
    //             points at, e.g. Loss_Payment.csv marking a Claim_Amount row as a loss payment.
    //   - LOOKUP: no "*_Identifier" column at all -> falls back to the first column as the key
    //             (handles small reference tables like Party_Role.csv keyed by a code).
    // Relationship/node identity is the same `name` + `sourceLabel` convention used everywhere
    // else in this file, so CSV-derived nodes show up in /knowledge/graph, /status, and delete
    // exactly like LLM-derived ones. Unlike processGraph, this is plain idempotent MERGE, so it
    // has no "already processed" guard and can be re-run or combined with a text-derived KB under
    // the same label. Row embeddings reuse CHUNK_SOURCE_TYPE (not a separate "csv_row" type):
    // they're full-sentence passages just like text chunks, and both findSimilarAllTypesByLabel's
    // native query and buildVectorContextFromMatches's chunk/entity split only treat
    // CHUNK_SOURCE_TYPE rows as retrievable passage text -- a third source type would be silently
    // invisible to vector search and, if the repository query were also widened, would still only
    // render as a bare name rather than the full row sentence.

    public Map<String, Object> importCsv(String label, List<Map<String, String>> files,
                                         boolean deepAnalysis) throws Exception {
        List<String> warnings = new ArrayList<>();
        List<CsvTable> tables = new ArrayList<>();
        for (Map<String, String> file : files) {
            String fileName = file.get("name");
            String content  = file.get("content");
            if (fileName == null || content == null || content.isBlank()) {
                warnings.add("Skipped empty file: " + fileName);
                continue;
            }
            String tableName = fileName.replaceAll("(?i)\\.csv$", "");
            List<Map<String, String>> rows = parseCsv(content);
            if (rows.isEmpty()) {
                warnings.add("No data rows in: " + fileName);
                continue;
            }
            tables.add(new CsvTable(tableName, new ArrayList<>(rows.get(0).keySet()), rows));
        }

        // Without a single "*_Identifier" column anywhere in the batch, the naming convention below
        // has nothing to go on and would turn every row into an isolated lookup node -- infer the
        // column roles from the data instead. Mixed batches keep the convention path untouched, so
        // convention-style lookup tables (e.g. Party_Role.csv) still link the way they always have.
        boolean conventionBatch = tables.stream()
                .anyMatch(t -> t.headers().stream().anyMatch(h -> h.endsWith("_Identifier")));
        if (!conventionBatch) {
            return importCsvInferred(label, tables, warnings, deepAnalysis);
        }

        int entityRows = 0, joinRows = 0, tagRows = 0, lookupRows = 0, relationshipsCreated = 0;
        List<String> embedNames = new ArrayList<>();
        List<String> embedTexts = new ArrayList<>();

        try (Session session = driver.session()) { // Neo4j
            for (CsvTable table : tables) {
                String tableLabel = sanitizeNeo4jName(table.name());
                List<String> idCols = table.headers().stream()
                        .filter(h -> h.endsWith("_Identifier")).toList();
                String ownPk = idCols.stream()
                        .filter(h -> h.equalsIgnoreCase(table.name() + "_Identifier"))
                        .findFirst().orElse(null);

                if (ownPk != null) {
                    List<String> fkCols = idCols.stream().filter(c -> !c.equals(ownPk)).toList();
                    for (Map<String, String> row : table.rows()) {
                        String idValue = row.get(ownPk);
                        if (idValue == null || idValue.isBlank()) continue;
                        String nodeName = tableLabel + "-" + idValue;
                        mergeCsvNode(session, label, tableLabel, nodeName, rowProperties(row, table.headers(), idCols));
                        for (String fk : fkCols) {
                            String fkValue = row.get(fk);
                            if (fkValue == null || fkValue.isBlank()) continue;
                            String targetLabel = sanitizeNeo4jName(stripIdentifierSuffix(fk));
                            String targetName  = targetLabel + "-" + fkValue;
                            mergeCsvNode(session, label, targetLabel, targetName, null);
                            mergeCsvRelationship(session, label, nodeName, targetName,
                                    "HAS_" + targetLabel.toUpperCase(), null);
                            relationshipsCreated++;
                        }
                        embedNames.add(nodeName);
                        // Only the row's own PK is excluded here (unlike the node's `props`, which
                        // drops all *_Identifier columns since those become graph edges instead) --
                        // vector search has no access to those edges, so FK values must stay in the
                        // embedded sentence as plain text or the row becomes unlinkable by meaning
                        // alone, e.g. a Claim_Amount row would never mention which Claim it belongs to.
                        embedTexts.add(csvRowSentence(tableLabel, idValue, row, table.headers(), List.of(ownPk)));
                        entityRows++;
                    }
                } else if (idCols.size() >= 2) {
                    String fromCol = idCols.get(0), toCol = idCols.get(1);
                    String fromLabel = sanitizeNeo4jName(stripIdentifierSuffix(fromCol));
                    String toLabel    = sanitizeNeo4jName(stripIdentifierSuffix(toCol));
                    String relType    = tableLabel.toUpperCase();
                    for (Map<String, String> row : table.rows()) {
                        String fromValue = row.get(fromCol), toValue = row.get(toCol);
                        if (fromValue == null || fromValue.isBlank() || toValue == null || toValue.isBlank()) continue;
                        String fromName = fromLabel + "-" + fromValue;
                        String toName   = toLabel + "-" + toValue;
                        mergeCsvNode(session, label, fromLabel, fromName, null);
                        mergeCsvNode(session, label, toLabel, toName, null);
                        mergeCsvRelationship(session, label, fromName, toName, relType,
                                rowProperties(row, table.headers(), List.of(fromCol, toCol)));
                        relationshipsCreated++;
                        joinRows++;
                    }
                } else if (idCols.size() == 1) {
                    String fkCol = idCols.get(0);
                    String targetLabel = sanitizeNeo4jName(stripIdentifierSuffix(fkCol));
                    for (Map<String, String> row : table.rows()) {
                        String fkValue = row.get(fkCol);
                        if (fkValue == null || fkValue.isBlank()) continue;
                        String targetName = targetLabel + "-" + fkValue;
                        mergeCsvNode(session, label, targetLabel, targetName, null);
                        tagCsvNode(session, label, targetName, tableLabel,
                                rowProperties(row, table.headers(), List.of(fkCol)));
                        tagRows++;
                    }
                } else {
                    String pkCol = table.headers().get(0);
                    for (Map<String, String> row : table.rows()) {
                        String idValue = row.get(pkCol);
                        if (idValue == null || idValue.isBlank()) continue;
                        String nodeName = tableLabel + "-" + idValue;
                        mergeCsvNode(session, label, tableLabel, nodeName,
                                rowProperties(row, table.headers(), List.of(pkCol)));
                        embedNames.add(nodeName);
                        embedTexts.add(csvRowSentence(tableLabel, idValue, row, table.headers(), List.of(pkCol)));
                        lookupRows++;
                    }
                }
            }
        }

        List<String> embedIds = embedNames.stream()
                .map(n -> label + "_csv_" + Math.abs(n.hashCode())).toList();
        int embedded = embedCsvRows(label, embedNames, embedIds, embedTexts);

        triply.syncGraphAsync(label); // TriplyDB mirror -- async, failures only logged

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("tablesProcessed", tables.size());
        result.put("entityRows", entityRows);
        result.put("joinRows", joinRows);
        result.put("tagRows", tagRows);
        result.put("lookupRows", lookupRows);
        result.put("relationshipsCreated", relationshipsCreated);
        result.put("rowsEmbedded", embedded);
        result.put("warnings", warnings);
        return result;
    }

    private record CsvTable(String name, List<String> headers, List<Map<String, String>> rows) {}

    private int embedCsvRows(String label, List<String> names, List<String> ids, List<String> texts) throws Exception {
        if (names.isEmpty()) return 0;
        repository.deleteBySourceTypeAndLabelsAndNameIn(CHUNK_SOURCE_TYPE, label, names); // PostgreSQL
        int embedded = 0;
        for (int i = 0; i < names.size(); i += EMBED_BATCH) {
            int end = Math.min(i + EMBED_BATCH, names.size());
            List<String> textBatch = texts.subList(i, end);
            List<float[]> vectors = jina.embed(textBatch);
            List<BikeEmbedding> rowsToSave = new ArrayList<>();
            for (int j = 0; j < textBatch.size(); j++) {
                rowsToSave.add(new BikeEmbedding(
                        CHUNK_SOURCE_TYPE, ids.get(i + j),
                        names.get(i + j), label, textBatch.get(j),
                        floatArrayToVectorString(vectors.get(j)),
                        "jina", vectors.get(j).length
                ));
            }
            repository.saveAll(rowsToSave); // PostgreSQL
            embedded += textBatch.size();
        }
        return embedded;
    }

    // ── Import CSV (inferred, for tables without "*_Identifier" columns) ──────────
    //
    // Plain CSVs such as `dish,cuisine,price_eur,ingredients` or `child,child_type,parent` carry
    // their structure in the data rather than in column names, so each column gets a role from
    // its name and values:
    //   - key:       the first column; each row is one node, named by the bare value (no table
    //                prefix), so the same "chicken" in two files is one node and the files link up
    //   - type:      "<x>_type" / "type" / "kind" / "class" -> that row's node label
    //   - HIERARCHY: "parent", "broader", "is_a", "subclass_of", "super*" -> (row)-[:IS_A]->(value)
    //   - NUMBER:    every value numeric -> typed property (so price comparisons work in Cypher)
    //   - LIST:      ';' or '|' separated values -> (row)-[:INCLUDES_<COLUMN>]->(each value)
    //   - CATEGORY:  short, repeating values -> (row)-[:OF_<COLUMN>]->(value) on a shared node
    //   - REFERENCE: any other text column where at least half the distinct values are keys of some
    //                table (or nodes already in this knowledge base) -> (row)-[:OF_<COLUMN>]->(value)
    //   - otherwise a string property.
    // Relationship types deliberately never start with HAS_: chat retrieval treats HAS_* as
    // structural scaffolding (sections, chunks) and never walks past it, which would hide every
    // CSV fact beyond the first hop.
    //
    // Once all rows are written, inferHierarchyRelationships() combines what the files share --
    // see there.

    private enum CsvColumnRole { HIERARCHY, NUMBER, LIST, REFERENCE, CATEGORY, PROPERTY }

    private record CsvTableProfile(CsvTable table, String keyCol, String typeCol, String defaultType,
                                   Map<String, CsvColumnRole> roles) {}

    private record CsvEdge(String from, String type, String to) {}

    private static final Pattern CSV_HIERARCHY_COLUMN = Pattern.compile(
            "(?i)^(parent([_ ]?(name|id))?|broader|is[_ ]?a|subclass[_ ]?of|super([_ ]?(class|type|category))?)$");
    private static final Pattern CSV_TYPE_COLUMN      = Pattern.compile("(?i)^(.+[_ ])?(type|kind|class)$");
    private static final Pattern CSV_LIST_SEPARATOR   = Pattern.compile("\\s*[;|]\\s*");
    private static final int     MAX_HIERARCHY_DEPTH  = 8;
    private static final Pattern CSV_PLACEHOLDER_NAME = Pattern.compile("(?i)null|none|n/?a|unknown|other|misc(ellaneous)?|-");

    private Map<String, Object> importCsvInferred(String label, List<CsvTable> tables,
                                                  List<String> warnings, boolean deepAnalysis) throws Exception {
        String safeLabel = sanitizeNeo4jName(label);

        // Lower-cased name -> stored name / node type. Seeded with nodes already in this knowledge
        // base so a CSV uploaded later (or a text-built graph) links to them instead of duplicating.
        Map<String, String> canonical = new HashMap<>();
        Map<String, String> nodeTypes = new HashMap<>();
        try (Session session = driver.session()) { // Neo4j
            session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) WHERE NOT n:Section AND NOT n:Chunk " +
                            "RETURN n.name AS name, labels(n) AS labels",
                    Map.of("l", label)
            ).forEachRemaining(r -> {
                String name = r.get("name").asString();
                String type = r.get("labels").asList(Value::asString).stream()
                        .filter(l -> !l.equals("KGNode") && !l.equals(safeLabel))
                        .findFirst().orElse("Entity");
                canonical.put(csvKey(name), name);
                nodeTypes.put(csvKey(name), type);
            });
        }

        // 1. Profile every table and register its row keys before writing anything, so a column
        //    can be recognised as a reference to another file's keys regardless of upload order.
        List<CsvTableProfile> profiles = tables.stream().map(KnowledgeService::profileCsvTable).toList();
        for (CsvTableProfile p : profiles) {
            for (Map<String, String> row : p.table().rows()) {
                String key = row.get(p.keyCol());
                if (key == null || key.isBlank()) continue;
                canonical.putIfAbsent(csvKey(key), key.trim());
                nodeTypes.put(csvKey(key), rowType(p, row));
            }
        }
        for (CsvTableProfile p : profiles) {
            // Only free-text columns can turn into references -- a CATEGORY column already links to
            // shared nodes, and re-checking it would flip its role on a re-import (its own values
            // exist as nodes by then).
            p.roles().replaceAll((col, role) -> {
                if (role != CsvColumnRole.PROPERTY) return role;
                Set<String> distinct = distinctValues(p.table(), col);
                long known = distinct.stream().filter(v -> nodeTypes.containsKey(csvKey(v))).count();
                return !distinct.isEmpty() && known * 2 >= distinct.size() ? CsvColumnRole.REFERENCE : role;
            });
        }

        // 2. Build the node/edge set in memory (merging everything that shares a name), then write.
        Map<String, Map<String, Object>> nodeProps  = new LinkedHashMap<>();
        Map<String, Set<String>>         nodeTables = new LinkedHashMap<>();
        Set<CsvEdge> edges = new LinkedHashSet<>();
        List<String> embedNames = new ArrayList<>(), embedIds = new ArrayList<>(), embedTexts = new ArrayList<>();
        Map<String, Map<String, String>> columnRoles = new LinkedHashMap<>();
        Set<String> listItemTypes = new LinkedHashSet<>();
        int rowNodes = 0;

        for (CsvTableProfile p : profiles) {
            CsvTable table = p.table();
            String tableLabel = sanitizeNeo4jName(table.name());
            Map<String, String> rolesOut = new LinkedHashMap<>();
            rolesOut.put(p.keyCol(), "KEY");
            if (p.typeCol() != null) rolesOut.put(p.typeCol(), "TYPE");
            p.roles().forEach((c, r) -> rolesOut.put(c, r.name()));
            columnRoles.put(table.name(), rolesOut);

            Map<String, String> parentTypes = new HashMap<>();
            p.roles().forEach((col, role) -> {
                if (role == CsvColumnRole.HIERARCHY) parentTypes.put(col, dominantKnownType(table, col, nodeTypes));
            });

            for (Map<String, String> row : table.rows()) {
                String keyValue = row.get(p.keyCol());
                if (keyValue == null || keyValue.isBlank()) continue;
                String rowKey = touchCsvNode(keyValue, rowType(p, row), table.name(),
                        canonical, nodeTypes, nodeProps, nodeTables);
                rowNodes++;

                for (Map.Entry<String, CsvColumnRole> col : p.roles().entrySet()) {
                    String raw = row.get(col.getKey());
                    if (raw == null || raw.isBlank()) continue;
                    String value = raw.trim();
                    String column = col.getKey();
                    switch (col.getValue()) {
                        case NUMBER   -> nodeProps.get(rowKey).put(column, parseCsvNumber(value));
                        case PROPERTY -> nodeProps.get(rowKey).put(column, value);
                        case HIERARCHY -> edges.add(new CsvEdge(rowKey, "IS_A", touchCsvNode(value,
                                parentTypes.get(column), table.name(), canonical, nodeTypes, nodeProps, nodeTables)));
                        case LIST -> {
                            String itemType = csvPascal(csvSingular(column));
                            for (String item : CSV_LIST_SEPARATOR.split(value)) {
                                if (item.isBlank()) continue;
                                String itemKey = touchCsvNode(item, itemType, table.name(),
                                        canonical, nodeTypes, nodeProps, nodeTables);
                                edges.add(new CsvEdge(rowKey, "INCLUDES_" + csvRelName(csvSingular(column)), itemKey));
                                listItemTypes.add(nodeTypes.get(itemKey));
                            }
                        }
                        case REFERENCE -> edges.add(new CsvEdge(rowKey, "OF_" + csvRelName(column), touchCsvNode(value,
                                csvPascal(csvSingular(column)), table.name(), canonical, nodeTypes, nodeProps, nodeTables)));
                        case CATEGORY -> edges.add(new CsvEdge(rowKey, "OF_" + csvRelName(column), touchCsvNode(value,
                                csvPascal(column), table.name(), canonical, nodeTypes, nodeProps, nodeTables)));
                    }
                }

                String nodeName = canonical.get(rowKey);
                embedNames.add(nodeName);
                embedIds.add(label + "_csv_" + Math.abs((table.name() + "|" + nodeName).hashCode()));
                embedTexts.add(csvRowSentence(tableLabel, keyValue.trim(), row, table.headers(), List.of(p.keyCol())));
            }
        }
        edges.removeIf(e -> e.from().equals(e.to()));

        try (Session session = driver.session()) { // Neo4j
            for (Map.Entry<String, Map<String, Object>> n : nodeProps.entrySet()) {
                // MERGE on identity only, then add labels -- MERGE with the labels in the pattern
                // would create a second node whenever an existing one carries a different label.
                session.run(String.format(
                        "MERGE (n:KGNode {name: $name, sourceLabel: $label}) " +
                        "ON CREATE SET n.nodeType = 'csv_entity' " +
                        "SET n:`%s`:`%s`, n += $props, " +
                        "    n.csvTables = reduce(acc = coalesce(n.csvTables, []), t IN $tables | " +
                        "                         CASE WHEN t IN acc THEN acc ELSE acc + t END)",
                        safeLabel, nodeTypes.get(n.getKey())),
                        Map.of("name", canonical.get(n.getKey()), "label", label,
                               "props", n.getValue(), "tables", List.copyOf(nodeTables.get(n.getKey()))));
            }
            for (CsvEdge e : edges) {
                session.run(String.format(
                        "MATCH (a:KGNode {name: $from, sourceLabel: $label}) " +
                        "MATCH (b:KGNode {name: $to,   sourceLabel: $label}) " +
                        "MERGE (a)-[:`%s`]->(b)", e.type()),
                        Map.of("from", canonical.get(e.from()), "to", canonical.get(e.to()), "label", label));
            }
        }

        // 3. Optional LLM pass that completes the hierarchy (places unclassified items, adds missing
        //    broader levels) -- before the derived-edge pass, so its links feed straight into it.
        Map<String, Object> taxonomy = null;
        if (deepAnalysis) {
            try {
                taxonomy = enrichCsvTaxonomy(label, listItemTypes);
            } catch (Exception e) {
                // Non-fatal -- the CSV graph is already written; enrichment is a bonus step.
                warnings.add("Taxonomy enrichment failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }

        // 4. Cross-file reasoning over the whole knowledge base (this and earlier imports).
        int inferred = inferHierarchyRelationships(label);
        long sharedNodes;
        try (Session session = driver.session()) { // Neo4j
            sharedNodes = session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) WHERE size(coalesce(n.csvTables, [])) > 1 RETURN count(n) AS c",
                    Map.of("l", label)
            ).single().get("c").asLong();
        }

        int embedded = embedCsvRows(label, embedNames, embedIds, embedTexts);

        triply.syncGraphAsync(label); // TriplyDB mirror -- async, failures only logged

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("mode", "inferred");
        result.put("tablesProcessed", tables.size());
        result.put("entityRows", rowNodes);
        result.put("joinRows", 0);
        result.put("tagRows", 0);
        result.put("lookupRows", 0);
        result.put("nodesWritten", nodeProps.size());
        result.put("relationshipsCreated", edges.size());
        result.put("inferredRelationships", inferred);
        if (taxonomy != null) result.put("taxonomy", taxonomy);
        result.put("sharedNodes", sharedNodes);
        result.put("rowsEmbedded", embedded);
        result.put("columnRoles", columnRoles);
        result.put("warnings", warnings);
        return result;
    }

    private static final String CSV_TAXONOMY_PLACE_PROMPT = """
            You are a taxonomy designer completing a classification hierarchy for a knowledge graph.
            You get the CURRENT HIERARCHY as an indented tree (broadest category first) and a list of
            UNPLACED items that have no category yet.

            Every link you add means "<child> is a kind of <parent>" and must be literally true.
            Before writing a link, say that sentence to yourself; if it is false or only loosely
            associated (an oil is not a nut, a wine is not a fruit, coffee is not a spice), choose
            or create a better category instead, or leave the item out.

            - Place each unplaced item under the most specific fitting category: an existing one,
              or a new one. New categories may sit under an existing category or another new one.
            - Add a missing intermediate level where it makes the tree clearer.
            - Match the naming style of the existing categories (casing, singular nouns, language);
              with no existing categories, use lowercase singular nouns ("vegetable", "dairy").

            Return ONLY a valid JSON object -- no markdown, no explanation:
            {
              "newCategories": [{"name": "<new category>", "parent": "<existing or new category, or null>"}],
              "placements":    {"<unplaced item>": "<existing or new category>"}
            }

            Rules:
            - "placements" keys MUST be names from the UNPLACED list, verbatim.
            - Never move anything that is already in the current hierarchy.
            - Leave an item out rather than forcing it into a category that doesn't fit. No cycles.
            """;

    private static final String CSV_TAXONOMY_GROUP_PROMPT = """
            You organise the TOP LEVEL of a classification hierarchy. You get the current top-level
            categories, each with a few example members. Where several of them are genuinely kinds
            of one broader thing, introduce that broader category and list them under it -- e.g.
            "fruit", "vegetable" and "herb" are all kinds of "plant product". Aim for a small number
            of meaningful top levels, but only group categories when "<category> is a kind of
            <broader category>" is literally true for each of them; leave the rest ungrouped.

            Return ONLY a valid JSON object -- no markdown, no explanation:
            {"groups": {"<broader category>": ["<top-level category>", "<top-level category>"]}}

            Rules:
            - List entries MUST be top-level category names from the input, verbatim; each may
              appear in at most one group.
            - A broader category may be a new name or one of the given top-level categories.
            - Every group needs at least two members. Match the existing naming style.
            - Return {"groups": {}} if nothing should be grouped.
            """;

    private static final String CSV_TAXONOMY_REVIEW_PROMPT = """
            You review proposed taxonomy links. Each numbered line is a claim
            "<child> is a kind of <parent>". When the child is itself a category, its members are
            listed in brackets.

            A claim is valid only if the child is genuinely a kind, subtype or instance of the
            parent -- being related to it, used with it or found in it is NOT enough. For a category
            child, the claim is valid only if EVERY listed member is also a kind of the parent
            (e.g. "spice [chili, star anise] is a kind of herb" is false; so is
            "grain [rice noodles] is a kind of gluten").

            Judge every claim independently. Return ONLY a valid JSON object -- no markdown, no
            explanation -- with one verdict per claim number:
            {"verdicts": {"1": true, "2": false}}
            """;

    /**
     * Opt-in ("deep analysis") LLM pass that completes the CSV-built hierarchy so it reads broad ->
     * specific instead of stopping at whatever the source files covered. Three focused calls --
     * one job each works far better than one call doing everything:
     *   1. place:  file every unclassified node of the hierarchy's item types (e.g. Ingredient)
     *              under an existing or new category (with no hierarchy at all: the list-column
     *              items, e.g. a dishes file alone, get a fresh one)
     *   2. group:  give loose top-level categories broader parents ("fruit", "vegetable" ->
     *              "plant product")
     *   3. review: judge every proposed "child is a kind of parent" claim on its own and drop the
     *              false ones -- the generating calls reliably slip in loose associations
     * Everything is then validated like detectAndStoreTaxonomy: only known unplaced items and
     * current top levels can be attached, nothing from the data is re-parented, an existing
     * non-category node never becomes a category, cycles are rejected, and new categories left
     * empty are dropped. LLM-made links are tagged {source: 'llm'}.
     */
    private Map<String, Object> enrichCsvTaxonomy(String label, Set<String> listItemTypes) throws Exception {
        String safeLabel = sanitizeNeo4jName(label);
        Map<String, String> parentOf = new LinkedHashMap<>(); // child -> parent (IS_A)
        Map<String, String> typeOf   = new LinkedHashMap<>(); // every entity node -> its type label
        try (Session session = driver.session()) { // Neo4j
            session.run(
                    "MATCH (c:KGNode {sourceLabel: $l})-[:IS_A]->(p:KGNode {sourceLabel: $l}) RETURN c.name AS c, p.name AS p",
                    Map.of("l", label)
            ).forEachRemaining(r -> parentOf.putIfAbsent(r.get("c").asString(), r.get("p").asString()));
            session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) WHERE NOT n:Section AND NOT n:Chunk " +
                            "RETURN n.name AS name, labels(n) AS labels",
                    Map.of("l", label)
            ).forEachRemaining(r -> typeOf.put(r.get("name").asString(),
                    r.get("labels").asList(Value::asString).stream()
                            .filter(l -> !l.equals("KGNode") && !l.equals(safeLabel))
                            .findFirst().orElse("Entity")));
        }

        Set<String> categories = new LinkedHashSet<>(parentOf.values());
        Set<String> inHierarchy = new HashSet<>(parentOf.keySet());
        inHierarchy.addAll(categories);
        Set<String> itemTypes = parentOf.isEmpty()
                ? listItemTypes
                : parentOf.keySet().stream().map(typeOf::get).filter(Objects::nonNull).collect(Collectors.toSet());
        List<String> unplaced = typeOf.entrySet().stream()
                .filter(e -> itemTypes.contains(e.getValue()) && !inHierarchy.contains(e.getKey()))
                .map(Map.Entry::getKey).sorted().toList();
        List<String> roots = categories.stream().filter(c -> !parentOf.containsKey(c)).sorted().toList();
        if (unplaced.isEmpty() && roots.size() < 2) {
            return Map.of("placed", 0, "newCategories", List.of(), "groupedTopLevels", 0, "rejected", 0);
        }

        // Name resolution: existing categories first, then new ones. A name the LLM uses as a
        // parent without declaring it ("beverage") still counts as a new category, but an existing
        // non-category node (an ingredient) is never promoted to one.
        Map<String, String> existing = new HashMap<>();
        typeOf.keySet().forEach(n -> existing.put(csvKey(n), n));
        Set<String> categoryKeys = categories.stream().map(KnowledgeService::csvKey).collect(Collectors.toSet());
        Map<String, String> newCats = new LinkedHashMap<>(); // lower -> name
        java.util.function.Function<Object, String> category = v -> {
            // LLMs write "null"/"none" for "no category" as often as a real JSON null.
            if (!(v instanceof String s) || s.isBlank() || CSV_PLACEHOLDER_NAME.matcher(s.trim()).matches()) return null;
            String k = csvKey(s);
            if (categoryKeys.contains(k)) return existing.get(k);
            if (newCats.containsKey(k)) return newCats.get(k);
            if (existing.containsKey(k)) return null;
            newCats.put(k, s.trim());
            return s.trim();
        };

        Set<String> unplacedSet = new HashSet<>(unplaced);
        Map<String, String> proposed = new LinkedHashMap<>(); // child -> parent
        int rejected = 0;

        // 1-3. Place -> (group) -> review, plus one repair round in which items whose placement the
        //      review rejected are placed again with that feedback (and reviewed again).
        List<String> reviewRejected = new ArrayList<>();
        List<String> reviewFailures = new ArrayList<>();
        Set<String> topLevelSet = new HashSet<>();
        List<String> remaining = unplaced;
        List<String> feedback = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            Map<String, String> fresh = new LinkedHashMap<>(); // this round's proposals, child -> parent
            Map<String, String> current = new LinkedHashMap<>(parentOf);
            current.putAll(proposed);

            // Place unclassified items.
            if (!remaining.isEmpty()) {
                StringBuilder prompt = new StringBuilder("CURRENT HIERARCHY:\n");
                appendForest(prompt, current, rootsOf(current));
                if (current.isEmpty()) prompt.append("(none yet)\n");
                prompt.append("\nUNPLACED:\n");
                remaining.forEach(n -> prompt.append("- ").append(n).append('\n'));
                if (!feedback.isEmpty()) {
                    prompt.append("\nREJECTED BY REVIEW -- these links are wrong, do not propose them again:\n");
                    feedback.forEach(r -> prompt.append("- ").append(r).append('\n'));
                }
                Map<String, Object> placed = askJson(CSV_TAXONOMY_PLACE_PROMPT, prompt.toString(), 8000);
                for (Map<String, Object> spec : asListOfMaps(placed.get("newCategories"))) {
                    String name = category.apply(spec.get("name"));
                    if (name == null) { rejected++; continue; }
                    if (spec.get("parent") == null || !newCats.containsKey(csvKey(name)) || current.containsKey(name)) continue;
                    String parent = category.apply(spec.get("parent"));
                    if (parent != null) fresh.put(name, parent); else rejected++;
                }
                Set<String> remainingSet = new HashSet<>(remaining);
                for (Map.Entry<String, Object> e : asMap(placed.get("placements")).entrySet()) {
                    String child = existing.get(csvKey(e.getKey())), parent = category.apply(e.getValue());
                    if (child != null && remainingSet.contains(child) && parent != null) fresh.put(child, parent); else rejected++;
                }
            }

            // Group the top level (existing roots plus new categories without a parent) -- once.
            if (round == 0) {
                Map<String, String> withFresh = new LinkedHashMap<>(current);
                withFresh.putAll(fresh);
                Map<String, List<String>> members = membersOf(withFresh);
                List<String> topLevels = new ArrayList<>(roots);
                newCats.values().stream().filter(c -> !withFresh.containsKey(c)).forEach(topLevels::add);
                topLevelSet.addAll(topLevels);
                if (topLevels.size() >= 3) {
                    StringBuilder prompt = new StringBuilder("TOP-LEVEL CATEGORIES:\n");
                    for (String t : topLevels) {
                        List<String> m = members.getOrDefault(t, List.of());
                        prompt.append("- ").append(t).append(" (e.g. ")
                                .append(String.join(", ", m.subList(0, Math.min(6, m.size())))).append(")\n");
                    }
                    Map<String, Object> grouped = askJson(CSV_TAXONOMY_GROUP_PROMPT, prompt.toString(), 4000);
                    for (Map.Entry<String, Object> g : asMap(grouped.get("groups")).entrySet()) {
                        if (!(g.getValue() instanceof List<?> list)) continue;
                        String parent = category.apply(g.getKey());
                        if (parent == null) { rejected++; continue; }
                        List<String> valid = list.stream()
                                .map(v -> v instanceof String s ? resolveName(s, existing, newCats) : null)
                                .filter(c -> c != null && topLevelSet.contains(c) && !c.equals(parent) && !fresh.containsKey(c))
                                .distinct().toList();
                        if (valid.size() < 2) { rejected += valid.size(); continue; }
                        valid.forEach(c -> fresh.put(c, parent));
                    }
                }
            }
            if (fresh.isEmpty()) break;

            // Review this round's claims, each on its own, with member context for categories.
            Map<String, String> context = new LinkedHashMap<>(current);
            context.putAll(fresh);
            Map<String, String> falseClaims = reviewTaxonomyClaims(fresh, membersOf(context), reviewFailures);
            falseClaims.forEach((child, parent) -> {
                fresh.remove(child);
                reviewRejected.add(child + " -> " + parent);
            });
            proposed.putAll(fresh);

            feedback = falseClaims.entrySet().stream()
                    .map(e -> e.getKey() + " is NOT a kind of " + e.getValue()).toList();
            remaining = falseClaims.keySet().stream().filter(unplacedSet::contains).sorted().toList();
            if (remaining.isEmpty()) break;
        }
        rejected += reviewRejected.size();

        // 4. Cycle guard, then drop new categories nothing ended up under (repeat: dropping one
        //    can empty the new category above it).
        Map<String, String> tree = new HashMap<>(parentOf);
        Map<String, String> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : proposed.entrySet()) {
            if (e.getKey().equals(e.getValue()) || isAncestor(e.getKey(), e.getValue(), tree)) { rejected++; continue; }
            tree.put(e.getKey(), e.getValue());
            accepted.put(e.getKey(), e.getValue());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String cat : List.copyOf(newCats.values())) {
                if (accepted.containsValue(cat)) continue;
                newCats.values().remove(cat);
                if (accepted.remove(cat) != null) rejected++;
                changed = true;
            }
        }

        String categoryType = categories.stream().map(typeOf::get).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("Category");
        try (Session session = driver.session()) { // Neo4j
            for (String cat : newCats.values()) {
                session.run(String.format(
                        "MERGE (n:KGNode {name: $name, sourceLabel: $label}) " +
                        "ON CREATE SET n.nodeType = 'csv_entity', n.inferredBy = 'llm' " +
                        "SET n:`%s`:`%s`", safeLabel, categoryType),
                        Map.of("name", cat, "label", label));
            }
            for (Map.Entry<String, String> e : accepted.entrySet()) {
                session.run(
                        "MATCH (c:KGNode {name: $child, sourceLabel: $label}) " +
                        "MATCH (p:KGNode {name: $parent, sourceLabel: $label}) " +
                        "MERGE (c)-[r:IS_A]->(p) ON CREATE SET r.source = 'llm'",
                        Map.of("child", e.getKey(), "parent", e.getValue(), "label", label));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("placed", accepted.keySet().stream().filter(unplacedSet::contains).count());
        out.put("unplacedBefore", unplaced.size());
        out.put("newCategories", List.copyOf(newCats.values()));
        out.put("groupedTopLevels", accepted.keySet().stream().filter(topLevelSet::contains).count());
        out.put("rejected", rejected);
        out.put("rejectedByReview", reviewRejected);
        if (!reviewFailures.isEmpty()) out.put("reviewFailed", reviewFailures);
        return out;
    }

    private Map<String, Object> askJson(String systemPrompt, String userContent, int maxTokens) throws Exception {
        String response = openAi.chatDesign(systemPrompt, userContent, maxTokens).strip();
        if (response.startsWith("```")) {
            response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
        return parsed;
    }

    /**
     * Returns the claims (child -> parent) the reviewer judged false. Retries once on an unusable
     * reply; if that fails too the claims are kept unreviewed and the failure is reported, rather
     * than silently passing everything through.
     */
    private Map<String, String> reviewTaxonomyClaims(Map<String, String> claims, Map<String, List<String>> members,
                                                     List<String> failures) {
        List<Map.Entry<String, String>> list = new ArrayList<>(claims.entrySet());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String child = list.get(i).getKey();
            List<String> m = members.getOrDefault(child, List.of());
            sb.append(i + 1).append(". ").append(child);
            if (!m.isEmpty()) sb.append(" [").append(String.join(", ", m.subList(0, Math.min(8, m.size())))).append(']');
            sb.append(" is a kind of ").append(list.get(i).getValue()).append('\n');
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Map<String, Object> verdicts = asMap(askJson(CSV_TAXONOMY_REVIEW_PROMPT, sb.toString(), 4000).get("verdicts"));
                if (verdicts.isEmpty()) throw new IllegalStateException("no verdicts in review reply");
                Map<String, String> falseClaims = new LinkedHashMap<>();
                for (int i = 0; i < list.size(); i++) {
                    if (Boolean.FALSE.equals(verdicts.get(String.valueOf(i + 1)))) {
                        falseClaims.put(list.get(i).getKey(), list.get(i).getValue());
                    }
                }
                return falseClaims;
            } catch (Exception e) {
                log.warn("[taxonomy] review attempt {} failed: {}", attempt, e.getMessage());
                if (attempt == 2) failures.add(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
        return Map.of();
    }

    private static Map<String, List<String>> membersOf(Map<String, String> parentOf) {
        Map<String, List<String>> members = new TreeMap<>();
        parentOf.forEach((c, p) -> members.computeIfAbsent(p, k -> new ArrayList<>()).add(c));
        members.values().forEach(Collections::sort);
        return members;
    }

    private static List<String> rootsOf(Map<String, String> parentOf) {
        return parentOf.values().stream().distinct().filter(p -> !parentOf.containsKey(p)).sorted().toList();
    }

    private static String resolveName(String raw, Map<String, String> existing, Map<String, String> newCats) {
        String k = csvKey(raw);
        return existing.containsKey(k) ? existing.get(k) : newCats.get(k);
    }

    private static void appendForest(StringBuilder sb, Map<String, String> parentOf, List<String> roots) {
        Map<String, List<String>> childrenOf = new TreeMap<>();
        parentOf.forEach((c, p) -> childrenOf.computeIfAbsent(p, k -> new ArrayList<>()).add(c));
        for (String root : roots) appendTree(sb, root, childrenOf, 0, new HashSet<>());
    }

    private static void appendTree(StringBuilder sb, String node, Map<String, List<String>> childrenOf,
                                   int depth, Set<String> path) {
        if (!path.add(node)) return; // cycle in source data
        sb.append("  ".repeat(depth)).append(node).append('\n');
        for (String child : childrenOf.getOrDefault(node, List.of())) {
            appendTree(sb, child, childrenOf, depth + 1, path);
        }
        path.remove(node);
    }

    private static boolean isAncestor(String candidate, String node, Map<String, String> parentOf) {
        Set<String> seen = new HashSet<>();
        for (String cur = node; cur != null && seen.add(cur); cur = parentOf.get(cur)) {
            if (cur.equals(candidate)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /**
     * Facts about a node that sits inside an IS_A hierarchy also hold for each of its ancestors:
     * "Pad Thai INCLUDES_INGREDIENT shrimp" + "shrimp IS_A shellfish IS_A seafood" =>
     * "Pad Thai INCLUDES_INGREDIENT_CATEGORY shellfish" and "... seafood" (with `via` naming the
     * ingredient that justifies it). This is what joins two files that only share nodes -- one
     * file's rows point at the nodes, the other file arranges them into a hierarchy. Materialising
     * the derived edges matters because chat retrieval only walks a few hops: without them,
     * "which dishes contain seafood?" needs dish -> shrimp -> shellfish -> seafood and back.
     * Runs over the whole knowledge base, so it also links CSVs imported in separate uploads.
     */
    private int inferHierarchyRelationships(String label) {
        int total = 0;
        try (Session session = driver.session()) { // Neo4j
            List<String> relTypes = session.run(
                    "MATCH (:KGNode {sourceLabel: $l})-[r]->(:KGNode {sourceLabel: $l})-[:IS_A]->(:KGNode {sourceLabel: $l}) " +
                            "WHERE type(r) <> 'IS_A' AND NOT coalesce(r.inferred, false) " +
                            "  AND NOT type(r) STARTS WITH 'HAS_' AND type(r) <> 'PARENT_OF' " +
                            "RETURN DISTINCT type(r) AS t",
                    Map.of("l", label)
            ).list(r -> r.get("t").asString());

            for (String relType : relTypes) {
                total += session.run(String.format(
                        "MATCH (a:KGNode {sourceLabel: $l})-[:`%1$s`]->(b:KGNode {sourceLabel: $l})" +
                        "-[:IS_A*1..%2$d]->(c:KGNode {sourceLabel: $l}) " +
                        "WHERE a <> c " +
                        "WITH a, c, collect(DISTINCT b.name) AS via " +
                        "MERGE (a)-[x:`%1$s_CATEGORY`]->(c) " +
                        "SET x.inferred = true, x.via = via " +
                        "RETURN count(x) AS n",
                        relType, MAX_HIERARCHY_DEPTH),
                        Map.of("l", label)
                ).single().get("n").asInt();
            }
        }
        return total;
    }

    private static CsvTableProfile profileCsvTable(CsvTable table) {
        String keyCol = table.headers().get(0);
        String typeCol = null;
        Map<String, CsvColumnRole> roles = new LinkedHashMap<>();
        int rows = table.rows().size();
        for (String col : table.headers()) {
            if (col.equals(keyCol)) continue;
            List<String> values = table.rows().stream().map(r -> r.get(col))
                    .filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
            Set<String> distinct = new HashSet<>(values);
            if (values.isEmpty()) {
                roles.put(col, CsvColumnRole.PROPERTY);
            } else if (typeCol == null && CSV_TYPE_COLUMN.matcher(col).matches()
                    && distinct.size() <= Math.max(3, rows / 2)) {
                typeCol = col;
            } else if (CSV_HIERARCHY_COLUMN.matcher(col).matches()) {
                roles.put(col, CsvColumnRole.HIERARCHY);
            } else if (values.stream().allMatch(KnowledgeService::isCsvNumber)) {
                roles.put(col, CsvColumnRole.NUMBER);
            } else if (values.stream().anyMatch(v -> CSV_LIST_SEPARATOR.matcher(v).find())) {
                roles.put(col, CsvColumnRole.LIST);
            } else if (distinct.size() < values.size()
                    && distinct.size() <= Math.max(3, values.size() * 0.6)
                    && values.stream().allMatch(v -> v.length() <= 40)) {
                roles.put(col, CsvColumnRole.CATEGORY);
            } else {
                roles.put(col, CsvColumnRole.PROPERTY);
            }
        }
        return new CsvTableProfile(table, keyCol, typeCol, csvPascal(csvSingular(keyCol)), roles);
    }

    private static String rowType(CsvTableProfile p, Map<String, String> row) {
        String t = p.typeCol() != null ? row.get(p.typeCol()) : null;
        return t != null && !t.isBlank() ? csvPascal(t) : p.defaultType();
    }

    /** Registers a node touched by this import and returns its lower-cased identity key. */
    private static String touchCsvNode(String rawName, String fallbackType, String tableName,
                                       Map<String, String> canonical, Map<String, String> nodeTypes,
                                       Map<String, Map<String, Object>> nodeProps,
                                       Map<String, Set<String>> nodeTables) {
        String key = csvKey(rawName);
        canonical.putIfAbsent(key, rawName.trim());
        nodeTypes.putIfAbsent(key, fallbackType);
        nodeProps.computeIfAbsent(key, k -> new LinkedHashMap<>());
        nodeTables.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(tableName);
        return key;
    }

    /** Label for hierarchy parents that never appear as rows ("animal product"): the most common label among those that do. */
    private static String dominantKnownType(CsvTable table, String col, Map<String, String> nodeTypes) {
        return distinctValues(table, col).stream()
                .map(v -> nodeTypes.get(csvKey(v)))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("Category");
    }

    private static Set<String> distinctValues(CsvTable table, String col) {
        return table.rows().stream().map(r -> r.get(col))
                .filter(v -> v != null && !v.isBlank()).map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String csvKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isCsvNumber(String v) {
        return v.matches("-?\\d+(\\.\\d+)?");
    }

    private static Object parseCsvNumber(String v) {
        return v.contains(".") ? (Object) Double.parseDouble(v) : (Object) Long.parseLong(v);
    }

    private static String csvSingular(String word) {
        String w = word.trim(), l = w.toLowerCase(Locale.ROOT);
        if (l.endsWith("ies") && w.length() > 3) return w.substring(0, w.length() - 3) + "y";
        if (l.matches(".*(ss|sh|ch|x|z)es$")) return w.substring(0, w.length() - 2);
        if (l.endsWith("s") && !l.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static String csvPascal(String s) {
        String out = Arrays.stream(s.trim().split("[^A-Za-z0-9]+"))
                .filter(p -> !p.isEmpty())
                .map(p -> Character.toUpperCase(p.charAt(0)) + p.substring(1))
                .collect(Collectors.joining());
        return out.isEmpty() ? "Entity" : out;
    }

    private static String csvRelName(String column) {
        String rel = column.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "").toUpperCase(Locale.ROOT);
        rel = rel.replaceFirst("^HAS_", "");
        return rel.isEmpty() ? "RELATED_TO" : rel;
    }

    private void mergeCsvNode(Session session, String label, String nodeLabel, String name,
                               Map<String, Object> props) {
        String safeLabel = label.replaceAll("[^a-zA-Z0-9]", "_");
        String safeType  = nodeLabel.replaceAll("[^a-zA-Z0-9]", "_");
        session.run(String.format( // Neo4j
                "MERGE (n:KGNode:`%s`:`%s` {name: $name, sourceLabel: $label}) " +
                "ON CREATE SET n.nodeType = 'csv_entity' " +
                "SET n += $props",
                safeLabel, safeType),
                Map.of("name", name, "label", label, "props", props != null ? props : Map.of()));
    }

    private void mergeCsvRelationship(Session session, String label, String fromName, String toName,
                                       String relType, Map<String, Object> props) {
        String safeRel = relType.replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
        session.run(String.format( // Neo4j
                "MATCH (a:KGNode {name: $from, sourceLabel: $label}) " +
                "MATCH (b:KGNode {name: $to,   sourceLabel: $label}) " +
                "MERGE (a)-[r:`%s`]->(b) " +
                "SET r += $props",
                safeRel),
                Map.of("from", fromName, "to", toName, "label", label, "props", props != null ? props : Map.of()));
    }

    private void tagCsvNode(Session session, String label, String name, String extraLabel,
                             Map<String, Object> props) {
        String safeExtra = extraLabel.replaceAll("[^a-zA-Z0-9]", "_");
        session.run(String.format( // Neo4j
                "MATCH (n:KGNode {name: $name, sourceLabel: $label}) " +
                "SET n:`%s` " +
                "SET n += $props",
                safeExtra),
                Map.of("name", name, "label", label, "props", props != null ? props : Map.of()));
    }

    private static String sanitizeNeo4jName(String s) {
        return s.replaceAll("[^a-zA-Z0-9]", "_");
    }

    private static String stripIdentifierSuffix(String column) {
        return column.replaceAll("(?i)_Identifier$", "");
    }

    private static Map<String, Object> rowProperties(Map<String, String> row, List<String> headers,
                                                       Collection<String> exclude) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (String h : headers) {
            if (exclude.contains(h)) continue;
            String v = row.get(h);
            if (v != null && !v.isBlank()) props.put(h, v);
        }
        return props;
    }

    private static String csvRowSentence(String tableLabel, String idValue, Map<String, String> row,
                                          List<String> headers, Collection<String> keyCols) {
        StringBuilder sb = new StringBuilder(tableLabel.replace('_', ' ')).append(' ').append(idValue).append(": ");
        boolean first = true;
        for (String h : headers) {
            if (keyCols.contains(h)) continue;
            String v = row.get(h);
            if (v == null || v.isBlank()) continue;
            if (!first) sb.append(", ");
            sb.append(h.replace('_', ' ')).append(' ').append(v);
            first = false;
        }
        return sb.append('.').toString();
    }

    // RFC4180-ish: handles quoted fields (commas/quotes inside quotes). First line is headers;
    // duplicate header names collapse to one key (last value wins) since rows are keyed by a Map.
    private static List<Map<String, String>> parseCsv(String content) {
        List<String> lines = Arrays.stream(content.split("\\r?\\n")).filter(l -> !l.isEmpty()).toList();
        if (lines.size() < 2) return List.of();
        List<String> headers = splitCsvLine(lines.get(0));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> values = splitCsvLine(lines.get(i));
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                row.put(headers.get(c), c < values.size() ? values.get(c).trim() : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else cur.append(c);
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',') { fields.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    // ── Process ──────────────────────────────────────────────────────────────

    public SseEmitter process(List<Map<String, String>> sections, String label) {
        SseEmitter emitter = new SseEmitter(900_000L);
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
                                    "droppedTriples", extracted.dropped().size(),
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

                triply.syncGraphAsync(label); // TriplyDB mirror -- async, failures only logged
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
                                    int topK, int neighborLimit, int maxHops, String ragMode, boolean strict,
                                    String seedPriority, boolean text2cypher) throws Exception {
        int effectiveTopK          = topK > 0         ? topK         : TOP_K;
        int effectiveNeighborLimit = neighborLimit > 0 ? neighborLimit : NEIGHBOR_LIMIT;
        // Unlike topK (a flat, single-query LIMIT), each additional hop runs one more Neo4j round
        // trip PER node discovered by the previous hop -- on a dense graph that's combinatorial,
        // not linear, so (unlike topK's cap, removed elsewhere by request) this stays clamped
        // rather than left unbounded.
        int effectiveMaxHops       = maxHops > 0 ? Math.min(maxHops, MAX_HOPS_CAP) : DEFAULT_MAX_HOPS;

        List<Map<String, Object>> graphContext;
        List<String>              seedNodes;
        String                    contextText;

        // Diagnostic collectors, surfaced to the FE alongside the answer so a response can show
        // exactly what was asked of each store -- every Cypher query actually run against Neo4j
        // (graph/combined modes), and every chunk/entity-name text actually returned by the
        // pgvector similarity search (vector/combined modes). Neither affects retrieval itself.
        List<Map<String, Object>> cypherTrace  = new ArrayList<>();
        List<Map<String, Object>> vectorChunks = new ArrayList<>();

        if ("vector".equals(ragMode)) {
            // PostgreSQL only — embed question, search both entity names and chunk text
            float[] vec = jina.embed(List.of(question)).get(0);
            List<BikeEmbedding> matches = repository.findSimilarAllTypesByLabel( // PostgreSQL
                    floatArrayToVectorString(vec), label, effectiveTopK);
            seedNodes    = matches.stream().map(BikeEmbedding::getName).collect(Collectors.toList());
            graphContext = List.of();
            contextText  = buildVectorContextFromMatches(matches, label);
            vectorChunks.addAll(toVectorChunkEntries(matches));

        } else if ("graph".equals(ragMode)) {
            // Neo4j only — keyword-match node names, expand relationships, skip PostgreSQL
            graphContext = buildKeywordGraphContext(question, label, effectiveTopK, effectiveNeighborLimit, effectiveMaxHops, cypherTrace);
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
            vectorChunks.addAll(toVectorChunkEntries(matches));
            List<Map<String, Object>> vectorCtx  = buildGraphContext(matches, label, effectiveNeighborLimit, effectiveMaxHops, cypherTrace);
            List<Map<String, Object>> keywordCtx = buildKeywordGraphContext(question, label, effectiveTopK, effectiveNeighborLimit, effectiveMaxHops, cypherTrace);

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

        List<Map<String, Object>> tokenUsage = new ArrayList<>();

        // Text-to-Cypher: retrieval hands the model a neighbourhood of facts, which is the wrong
        // tool for counts, rankings, averages and negation over the whole knowledge base (it has
        // to find and combine every relevant fact itself, and the context may not hold them all).
        // An LLM-written read-only query computes those exactly; its result goes at the top of the
        // context, with the retrieved facts kept below for detail.
        Map<String, Object> text2cypherResult = null;
        if (text2cypher && !"vector".equals(ragMode)) {
            text2cypherResult = runText2Cypher(question, label, cypherTrace, tokenUsage);
            Object block = text2cypherResult.remove("contextBlock");
            if (block instanceof String b) contextText = b + "\n\n" + contextText;
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

        // Only mentioned when the block is actually there -- otherwise the model starts quoting a
        // "QUERY RESULT" it was never given.
        String queryResultHandling = contextText.startsWith("QUERY RESULT")
                ? "The context starts with a QUERY RESULT section: an exact computation over the whole " +
                  "knowledge base. Base counts, totals, rankings, lists and negation on it, and use the " +
                  "remaining context only for supporting detail. "
                : "";

        // strict=true: model must answer only from context, no pre-training knowledge allowed.
        // strict=false (default): model may supplement with general knowledge when context is thin.
        String systemPrompt = strict
                ? "You are a retrieval system, not an assistant. " +
                  "Answer using ONLY the exact facts stated in the context below. " +
                  "Do not add background, explanation, or any information not explicitly present in the context. " +
                  "Do not say 'however' or 'historically' or reference any outside knowledge. " +
                  // Counting/ranking questions used to flip between an answer and a refusal: a
                  // computed result is never "stated" verbatim, so a literal reading of the rule
                  // above forbids it. Spell out that computing over the given facts is allowed.
                  "Computing over the facts in the context IS answering from the context: you may count, " +
                  "compare, rank, filter, intersect, average, and conclude that something is absent when " +
                  "no fact in the context supports it. When you do, list the facts you computed from " +
                  "(e.g. the dishes you counted) so the result can be checked. Answer in plain sentences " +
                  "or a short list, never by copying raw rows. " + queryResultHandling +
                  "If the answer cannot be found in or computed from the context, output exactly this and " +
                  "nothing else: 'Not in knowledge base.' " + possibleRelHandling + "\n\nContext:\n" + contextText
                : "You are a knowledgeable assistant. Use the following knowledge graph context to answer accurately. " +
                  "If the answer is not in the context, say so honestly. " + queryResultHandling +
                  "You may also go beyond what's directly stated and reason out likely reasons, causes, or " +
                  "consequences that the context strongly implies but doesn't say outright — as long as you " +
                  "clearly label any such reasoning as a possible/inferred conclusion, separate from stated facts, " +
                  "the same way you would label a POSSIBLE_ relationship. " + possibleRelHandling +
                  "\n\nContext:\n" + contextText;

        // strict mode's whole point is deterministic, context-only output -- a non-zero
        // temperature works against that (sampling randomness can still vary phrasing, or in
        // edge cases how faithfully the model follows the "answer only from context" instruction),
        // so strict always forces greedy decoding regardless of whatever temperature was requested.
        double effectiveTemperature = strict ? 0.0 : (temperature > 0 ? temperature : 0.7);

        List<Map<String, String>> messages = new ArrayList<>(history);
        messages.add(0, Map.of("role", "system", "content", systemPrompt));

        OpenAiService.ChatResult answerResult = openAi.chatDesignWithUsage(
                effectiveTemperature,
                maxTokens > 0 ? maxTokens : 800,
                messages
        );
        String answer = answerResult.content();

        tokenUsage.add(Map.of(
                "step", "answer generation",
                "promptTokens", answerResult.promptTokens(),
                "completionTokens", answerResult.completionTokens(),
                "totalTokens", answerResult.totalTokens()
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer",       answer);
        result.put("label",        label);
        result.put("question",     question);
        result.put("graphContext", graphContext);
        result.put("cypherTrace",  cypherTrace);
        result.put("vectorChunks", vectorChunks);
        result.put("tokenUsage",   tokenUsage);
        if (text2cypherResult != null) result.put("text2cypher", text2cypherResult);
        // Strict mode's entire contract is "don't say anything the context doesn't support" --
        // the prompt instructs that, but a prompt is a strong steering signal, not a technical
        // guarantee (observed directly: a model can still state a real-world fact about a
        // recognized entity that was never in the context at all). A second, independent LLM
        // call that only sees the context and the finished answer -- never asked to produce an
        // answer itself -- checks whether every claim is actually supported. Only run under
        // strict, since non-strict mode explicitly permits labeled inference beyond the context,
        // which this check would otherwise flag as a false positive on every answer.
        if (strict) {
            // Appends its own "grounding check" entry to the same tokenUsage list already stored
            // in `result` above (list is shared by reference, not copied) -- so no second put needed.
            Map<String, Object> grounding = checkGrounding(answer, contextText, tokenUsage);
            if (text2cypherResult != null && text2cypherResult.get("rows") instanceof List<?> rows) {
                grounding = clearClaimsVerifiedByQuery(grounding, rows);
            }
            result.put("groundingCheck", grounding);
        }
        result.put("retrievalInfo", Map.of(
                "mode",            ragMode,
                "seedPriority",    seedPriority,
                "strict",          strict,
                "temperature",     effectiveTemperature,
                "seedNodes",       seedNodes,
                "contextTriplets", graphContext.size(),
                "topK",            effectiveTopK,
                "neighborLimit",   effectiveNeighborLimit,
                "maxHops",         effectiveMaxHops,
                "text2cypher",     text2cypherResult != null
        ));
        return result;
    }

    // ── Text-to-Cypher ────────────────────────────────────────────────────────

    private static final String TEXT2CYPHER_PROMPT = """
            You translate a question into ONE read-only Neo4j Cypher query over a knowledge graph.
            You get the graph SCHEMA: node types with their property keys and entity names, and the
            relationship patterns that exist between node types.

            Rules:
            - Every node pattern MUST include {sourceLabel: $label} -- the database holds several
              knowledge bases and $label selects this one. Use the KGNode label plus a node type,
              e.g. (d:KGNode:Dish {sourceLabel: $label}).
            - Read-only: MATCH, OPTIONAL MATCH, WHERE, WITH, UNWIND, RETURN, ORDER BY, LIMIT and
              EXISTS { } / COUNT { } subqueries only.
            - Refer to entities by their exact names from the schema (case-sensitive), e.g.
              {name: 'gluten'}.
            - Relationship types ending in _CATEGORY are precomputed shortcuts: (a)-[:X_CATEGORY]->(c)
              means a reaches category c through any depth of IS_A. Prefer them for category
              questions ("contains dairy", "vegetarian", "allergy") over IS_A* paths.
            - Return only the values the answer needs, with readable aliases -- names, counts,
              numbers -- never whole nodes. Use RETURN DISTINCT for lists. For counts, rankings and
              averages also return the items the value was computed from, e.g.
              collect(DISTINCT d.name) AS dishes. LIMIT 100 at most.
            - A concept that is not a node name ("vegetarian", "vegan", "safe for a nut allergy",
              "without X") must be expressed through the categories that define it, using the
              CATEGORY HIERARCHY: vegetarian = contains nothing under meat or seafood; vegan =
              contains nothing under the animal-product category; "safe for X" = contains nothing
              under X. Pick the broadest category that covers the concept.
            - Negation goes on the entity you return, never on one of its parts:
              MATCH (d:KGNode:Dish {sourceLabel: $label})
              WHERE NOT EXISTS { (d)-[:INCLUDES_INGREDIENT_CATEGORY]->(:KGNode {sourceLabel: $label, name: 'meat'}) }
              -- not "ingredients that are not meat", which matches every dish with a vegetable.
            - If the schema cannot answer the question, set "cypher" to null.

            Return ONLY a valid JSON object -- no markdown, no explanation:
            {"cypher": "<query or null>"}
            """;

    private static final Pattern CYPHER_WRITE_CLAUSE = Pattern.compile(
            "(?i)\\b(CREATE|MERGE|DELETE|DETACH|SET|REMOVE|DROP|FOREACH|CALL|LOAD\\s+CSV|USE)\\b");
    private static final int TEXT2CYPHER_MAX_ROWS = 100;

    // label|question|schema-hash -> a query that ran successfully. OpenAI's seed is only
    // best-effort, so regenerating can yield a slightly different query (and so a differently
    // worded answer) for the same question; reusing the first good one keeps repeats identical.
    // The schema hash invalidates it as soon as the knowledge base's data changes.
    private final com.github.benmanes.caffeine.cache.Cache<String, String> text2cypherCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(500).build();

    /**
     * Has the LLM write one read-only query for the question, runs it, and returns
     * {cypher, columns, rows, rowCount, attempts, error?} plus a "contextBlock" for the answer
     * prompt. Guarded three ways: the query must reference $label (so it stays inside this
     * knowledge base), write clauses are rejected before it runs, and it runs in a READ-access
     * session with a timeout, so even a query that slips past the check can't modify anything.
     * One retry feeds a failing query's error back to the LLM.
     */
    private Map<String, Object> runText2Cypher(String question, String label,
                                               List<Map<String, Object>> cypherTrace,
                                               List<Map<String, Object>> tokenUsage) {
        Map<String, Object> out = new LinkedHashMap<>();
        String schema;
        try {
            schema = describeSchema(label);
        } catch (Exception e) {
            out.put("error", "schema lookup failed: " + e.getMessage());
            return out;
        }

        String cacheKey = label + "|" + question.trim().toLowerCase(Locale.ROOT) + "|" + schema.hashCode();
        String feedback = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            out.put("attempts", attempt);
            String cypher = attempt == 1 ? text2cypherCache.getIfPresent(cacheKey) : null;
            try {
                if (cypher != null) {
                    out.put("cached", true);
                } else {
                    OpenAiService.ChatResult gen = openAi.chatDesignWithUsage(0.0, 1500, List.of(
                            Map.of("role", "system", "content", TEXT2CYPHER_PROMPT),
                            Map.of("role", "user", "content", "SCHEMA:\n" + schema + "\nQUESTION: " + question + feedback)));
                    tokenUsage.add(Map.of(
                            "step", "text-to-cypher" + (attempt > 1 ? " (retry)" : ""),
                            "promptTokens", gen.promptTokens(),
                            "completionTokens", gen.completionTokens(),
                            "totalTokens", gen.totalTokens()));
                    String response = gen.content().strip();
                    if (response.startsWith("```")) {
                        response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
                    }
                    Object raw = asMap(objectMapper.readValue(response, Map.class)).get("cypher");
                    if (!(raw instanceof String s) || s.isBlank()) {
                        out.put("cypher", null);
                        out.put("error", "the question can't be answered from this graph's schema");
                        return out;
                    }
                    cypher = s.strip().replaceAll(";\\s*$", "");
                }
                out.put("cypher", cypher);
                if (cypher.contains(";")) throw new IllegalArgumentException("only a single statement is allowed");
                if (CYPHER_WRITE_CLAUSE.matcher(cypher).find()) throw new IllegalArgumentException("write clauses are not allowed");
                if (!cypher.contains("$label")) throw new IllegalArgumentException("query must filter nodes by {sourceLabel: $label}");

                List<String> columns;
                List<Map<String, Object>> rows = new ArrayList<>();
                try (Session session = driver.session(SessionConfig.builder()
                        .withDefaultAccessMode(AccessMode.READ).build())) { // Neo4j, read-only
                    Result res = session.run(new Query(cypher, Map.of("label", label)),
                            TransactionConfig.builder().withTimeout(java.time.Duration.ofSeconds(15)).build());
                    columns = res.keys();
                    while (res.hasNext() && rows.size() < TEXT2CYPHER_MAX_ROWS) {
                        Record r = res.next();
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (String col : columns) row.put(col, plainValue(r.get(col)));
                        rows.add(row);
                    }
                }
                cypherTrace.add(Map.of("stage", "text-to-cypher", "query", cypher,
                        "params", Map.of("label", label), "resultCount", rows.size()));
                text2cypherCache.put(cacheKey, cypher);
                out.remove("error");
                out.put("columns", columns);
                out.put("rows", rows);
                out.put("rowCount", rows.size());

                StringBuilder block = new StringBuilder(
                        "QUERY RESULT (exact Cypher computation over the whole knowledge base):\n")
                        .append("Query: ").append(cypher).append('\n');
                if (rows.isEmpty()) {
                    block.append("The query returned no rows -- nothing in the knowledge base matches it.\n");
                } else {
                    for (Map<String, Object> row : rows) block.append(objectMapper.writeValueAsString(row)).append('\n');
                }
                out.put("contextBlock", block.toString());
                return out;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                out.put("error", msg);
                log.warn("[text2cypher] attempt {} failed: {}", attempt, msg);
                feedback = "\n\nYOUR PREVIOUS QUERY FAILED -- fix it:\n" + cypher + "\nError: " + msg;
            }
        }
        return out;
    }

    /** Compact schema for the Text-to-Cypher prompt: node types (props, names) and relationship patterns. */
    private String describeSchema(String label) {
        String safeLabel = sanitizeNeo4jName(label);
        StringBuilder sb = new StringBuilder("Node types:\n");
        try (Session session = driver.session()) { // Neo4j
            session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) WHERE NOT n:Chunk AND NOT n:Section " +
                    "UNWIND [x IN labels(n) WHERE x <> 'KGNode' AND x <> $safe] AS t " +
                    "WITH t, count(n) AS c, collect(n.name) AS names, collect(keys(n)) AS ks " +
                    "RETURN t, c, names, reduce(acc = [], k IN reduce(f = [], l IN ks | f + l) | " +
                    "       CASE WHEN k IN acc THEN acc ELSE acc + k END) AS props ORDER BY t",
                    Map.of("l", label, "safe", safeLabel)
            ).forEachRemaining(r -> {
                List<String> names = new ArrayList<>(r.get("names").asList(Value::asString));
                Collections.sort(names);
                List<String> props = new ArrayList<>(r.get("props").asList(Value::asString));
                props.removeAll(List.of("sourceLabel", "nodeType", "csvTables", "inferredBy"));
                Collections.sort(props);
                sb.append("- ").append(r.get("t").asString()).append(" (").append(r.get("c").asLong())
                        .append(" nodes; properties: ").append(String.join(", ", props)).append(")\n")
                        .append("  names: ").append(String.join(", ", names.subList(0, Math.min(80, names.size()))))
                        .append(names.size() > 80 ? ", ..." : "").append('\n');
            });
            sb.append("Relationship patterns (count):\n");
            session.run(
                    "MATCH (a:KGNode {sourceLabel: $l})-[r]->(b:KGNode {sourceLabel: $l}) " +
                    "WHERE NOT a:Chunk AND NOT b:Chunk " +
                    "WITH [x IN labels(a) WHERE x <> 'KGNode' AND x <> $safe][0] AS from, type(r) AS rel, " +
                    "     [x IN labels(b) WHERE x <> 'KGNode' AND x <> $safe][0] AS to, count(*) AS c " +
                    "RETURN from, rel, to, c ORDER BY c DESC, rel, from, to LIMIT 60",
                    Map.of("l", label, "safe", safeLabel)
            ).forEachRemaining(r -> sb.append("- (").append(r.get("from").asString("?")).append(")-[:")
                    .append(r.get("rel").asString()).append("]->(").append(r.get("to").asString("?"))
                    .append(") x").append(r.get("c").asLong()).append('\n'));

            // The IS_A tree (broad -> specific) with leaves summarised as counts -- lets the model
            // map a concept like "vegan" onto the right broad category instead of guessing.
            Map<String, String> parentOf = new HashMap<>();
            session.run(
                    "MATCH (c:KGNode {sourceLabel: $l})-[:IS_A]->(p:KGNode {sourceLabel: $l}) RETURN c.name AS c, p.name AS p",
                    Map.of("l", label)
            ).forEachRemaining(r -> parentOf.putIfAbsent(r.get("c").asString(), r.get("p").asString()));
            if (!parentOf.isEmpty()) {
                Map<String, List<String>> childrenOf = membersOf(parentOf);
                sb.append("Category hierarchy (IS_A, broad -> specific; leaf items counted):\n");
                for (String root : rootsOf(parentOf)) appendCategoryTree(sb, root, childrenOf, 0, new HashSet<>());
            }
        }
        return sb.toString();
    }

    private static void appendCategoryTree(StringBuilder sb, String node, Map<String, List<String>> childrenOf,
                                           int depth, Set<String> path) {
        if (!path.add(node)) return;
        List<String> kids = childrenOf.getOrDefault(node, List.of());
        List<String> leaves = kids.stream().filter(k -> !childrenOf.containsKey(k)).toList();
        sb.append("  ".repeat(depth + 1)).append(node);
        if (!leaves.isEmpty()) sb.append(" (").append(leaves.size()).append(" items, e.g. ")
                .append(String.join(", ", leaves.subList(0, Math.min(3, leaves.size())))).append(')');
        sb.append('\n');
        for (String k : kids) if (childrenOf.containsKey(k)) appendCategoryTree(sb, k, childrenOf, depth + 1, path);
        path.remove(node);
    }

    /** Neo4j value -> JSON-friendly Java value (nodes become their name, relationships their type). */
    private static Object plainValue(Value v) {
        if (v == null || v.isNull()) return null;
        switch (v.type().name()) {
            case "NODE": {
                var node = v.asNode();
                return node.containsKey("name") ? node.get("name").asString() : node.elementId();
            }
            case "RELATIONSHIP": return v.asRelationship().type();
            case "PATH": {
                List<Object> parts = new ArrayList<>();
                v.asPath().nodes().forEach(n -> parts.add(n.containsKey("name") ? n.get("name").asString() : n.elementId()));
                return parts;
            }
            case "LIST": return v.asList(KnowledgeService::plainValue);
            case "MAP": {
                Map<String, Object> m = new LinkedHashMap<>();
                v.asMap(KnowledgeService::plainValue).forEach(m::put);
                return m;
            }
            default: return v.asObject();
        }
    }

    /**
     * The LLM checker reliably flags items an exact query returned (e.g. a dish name) when the
     * retrieved facts beside it happen not to mention them -- even when told the query result is
     * authoritative. A flagged claim that is nothing more than a value from the query rows is
     * verified by the query itself, so clear it deterministically; anything asserting more than
     * that (a different price, an extra property) is left for the LLM's verdict.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> clearClaimsVerifiedByQuery(Map<String, Object> grounding, List<?> rows) {
        if (!(grounding.get("unsupportedClaims") instanceof List<?> claims) || claims.isEmpty()) return grounding;
        Set<String> values = new HashSet<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> m) collectPlainValues(m.values(), values);
        }
        List<String> remaining = new ArrayList<>(), verified = new ArrayList<>();
        for (Object c : claims) {
            String claim = String.valueOf(c);
            String norm = claim.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}.]+", " ").trim();
            if (values.contains(norm)) verified.add(claim); else remaining.add(claim);
        }
        if (verified.isEmpty()) return grounding;
        Map<String, Object> out = new LinkedHashMap<>(grounding);
        out.put("unsupportedClaims", remaining);
        out.put("grounded", remaining.isEmpty());
        out.put("verifiedByQuery", verified);
        return out;
    }

    private static void collectPlainValues(Collection<?> in, Set<String> out) {
        for (Object v : in) {
            if (v instanceof Collection<?> c) collectPlainValues(c, out);
            else if (v instanceof Map<?, ?> m) collectPlainValues(m.values(), out);
            else if (v != null) out.add(String.valueOf(v).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}.]+", " ").trim());
        }
    }

    private static final String GROUNDING_CHECK_PROMPT = """
            You are a fact-checker. You will be given a CONTEXT and an ANSWER that was supposed to
            be derived only from that context. Identify any factual claim in the ANSWER that is
            NOT actually supported by the CONTEXT -- including a claim that happens to be true in
            the real world but isn't stated or implied by this specific context. The context
            defines a closed, self-contained world; anything the answer adds from outside it,
            even about an entity you recognize, counts as unsupported.

            Return ONLY a valid JSON object -- no markdown, no explanation:
            {
              "grounded": true | false,
              "unsupportedClaims": ["claim 1", "claim 2"]
            }

            Rules:
            - "grounded" is false whenever unsupportedClaims is non-empty, true otherwise.
            - Only flag factual claims (who did what, a relationship, a number, a date) -- never
              flag the answer's own phrasing, formatting, or a reasonable paraphrase of a fact the
              context does state.
            - A claim explicitly labeled by the answer as inferred/possible (e.g. "Possible reason
              (inferred, not explicitly stated): ...") is not itself a violation -- only flag it if
              even the underlying fact it reasons from isn't in the context.
            - A uniqueness, completeness, or count claim ("X is the only one with property Y",
              "there are exactly N") IS grounded as long as every entity the context mentions with
              that property is accounted for in the claim -- i.e. it correctly follows from
              everything the context states, even though no single sentence spells out the word
              "only" or the number. Do not flag correct reasoning over the full context as
              unsupported just because the conclusion itself isn't stated verbatim somewhere.
            - The same holds for every other computation over context facts: a ranking or
              maximum ("cuisine X has the most ..."), an average or sum, a filter ("dishes under
              10 EUR"), an intersection, or an absence ("no Thai dish contains shellfish") is
              grounded when it correctly follows from the facts in the context. Check the
              arithmetic and flag it only if it is actually wrong or rests on a missing fact.
            - An AUTHORITATIVE QUERY RESULT section is an exact computation over the whole knowledge
              base: every item it returns has the property its query selects for (e.g. a query
              filtering dishes to those with both dairy and gluten proves each returned dish has
              both). A claim that matches it is grounded. CONTEXT is only a partial sample of facts
              -- a fact missing from CONTEXT is never evidence against the query result.
            - If the answer correctly declines to answer (e.g. "Not in knowledge base."), grounded
              is true and unsupportedClaims is empty.
            - Quote each unsupported claim close to how the answer phrased it, so it's clear which
              part of the answer is the problem.
            """;

    /**
     * Post-hoc faithfulness check for a strict-mode answer: a second LLM call that only sees the
     * context and the already-generated answer (never asked to produce an answer of its own), and
     * reports any claim not actually supported by that context. Failing open on error -- a check
     * that couldn't run reports "unknown" (grounded: null) rather than a false "clean" or a false
     * alarm, since the main answer is already generated either way.
     */
    private Map<String, Object> checkGrounding(String answer, String contextText, List<Map<String, Object>> tokenUsage) {
        try {
            // A Text-to-Cypher result leads the context; hand it over as its own section, or the
            // checker weighs each claim against the (deliberately partial) retrieved facts below it
            // and flags items only the query result lists.
            String queryResult = "";
            String facts = contextText;
            if (contextText.startsWith("QUERY RESULT")) {
                int split = contextText.indexOf("\n\n");
                queryResult = split < 0 ? contextText : contextText.substring(0, split);
                facts = split < 0 ? "" : contextText.substring(split + 2);
            }
            String input = queryResult.isEmpty()
                    ? String.format("""
                    CONTEXT:
                    %s

                    ANSWER:
                    %s
                    """, contextText, answer)
                    : String.format("""
                    AUTHORITATIVE QUERY RESULT (exact computation over the whole knowledge base --
                    every item and value listed here is supported, even if CONTEXT below doesn't
                    mention it):
                    %s

                    CONTEXT:
                    %s

                    ANSWER:
                    %s
                    """, queryResult, facts, answer);
            // Temperature 0 (+ the fixed seed it implies) so the same answer gets the same verdict.
            OpenAiService.ChatResult checkResult = openAi.chatDesignWithUsage(0.0, 1000, List.of(
                    Map.of("role", "system", "content", GROUNDING_CHECK_PROMPT),
                    Map.of("role", "user", "content", input)));
            tokenUsage.add(Map.of(
                    "step", "grounding check",
                    "promptTokens", checkResult.promptTokens(),
                    "completionTokens", checkResult.completionTokens(),
                    "totalTokens", checkResult.totalTokens()
            ));
            String response = checkResult.content().strip();
            if (response.startsWith("```")) {
                response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
            return parsed;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("grounded", null);
            result.put("unsupportedClaims", List.of());
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return result;
        }
    }

    // ── Compare mode: judge two answers ─────────────────────────────────────────

    private static final String COMPARE_PROMPT = """
            You are an impartial judge comparing two candidate answers to the same question,
            produced by two different retrieval strategies over the same knowledge base. Decide
            which answer is better supported, more accurate, and more directly responsive to the
            question -- or whether they're genuinely equivalent.

            Return ONLY a valid JSON object -- no markdown, no explanation outside the JSON. All
            four keys are REQUIRED in every response, including failureModeA/failureModeB: when a
            pattern doesn't apply, write the literal JSON value null for that key -- never omit the
            key itself.
            {
              "verdict": "A" | "B" | "tie",
              "explanation": "...",
              "failureModeA": "hallucination" | "false_completeness" | null,
              "failureModeB": "hallucination" | "false_completeness" | null
            }

            Judge on:
            - Faithfulness: does the answer avoid claims unsupported by its own retrieved context?
            - Completeness: does it actually address every part of the question?
            - Specificity: does it name concrete entities/relationships rather than vague
              generalities?
            - Directness: does it answer the question asked, not a nearby one?

            Additionally, check each answer for these two specific, common failure patterns and set
            failureModeA/failureModeB accordingly (independently -- either, both, or neither answer
            can show one):
            - "hallucination": the answer states something with unwarranted confidence that isn't
              actually grounded in its own context -- a plausible-sounding claim that doesn't follow
              from what was retrieved (e.g. inferring compatibility/equivalence from surface
              similarity rather than an actual stated fact). A hedge like "I cannot confirm this
              from the given context" is NOT a hallucination -- it's an honest non-answer; only flag
              genuine confident-but-unsupported assertions.
            - "false_completeness": the answer presents a count or list as if exhaustive (a
              definite "there are N" or "these are all of them") when it's actually a partial
              subset -- confidently wrong about completeness, not just imprecise.
            An answer can have both, one, or neither. Most answers will have neither -- null is the
            common case, don't force a diagnosis onto an answer that's simply correct or simply
            incomplete-but-appropriately-hedged.

            "explanation" must be 2-4 sentences, specific about WHY one answer wins (or why they
            tie) -- not a generic restatement of the judging criteria. If you set a failureMode on
            either side, name it explicitly in the explanation with the specific unsupported claim
            or the specific missing items, not just the label.
            """;

    /**
     * LLM-as-judge for the FE's "compare" rag mode, which sends the same question through two
     * retrieval strategies and gets back two separate answers. modeA/modeB are the caller's own
     * labels for each side (e.g. "vector"/"graph") -- the returned verdict is normalized back
     * to one of those two labels (or "tie") rather than the generic "A"/"B" the LLM reasons in,
     * so the FE can compare it directly against the ragMode already on each message.
     *
     * Also returns failureModeA/failureModeB (each "hallucination" | "false_completeness" | null),
     * a fixed-position pair -- A always describes answerA's failure mode, B always answerB's, no
     * remapping needed since (unlike verdict) the LLM isn't choosing which side to describe.
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
        triply.deleteGraphAsync(label); // TriplyDB — drops the mirrored named graph
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
            long embeddings = repository.countByLabels(label); // PostgreSQL -- all source types
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

    // Flattens pgvector matches into plain text entries for the FE -- textContent is the real
    // passage for a chunk-type match, or just a repeat of the entity name for a name-type match
    // (see how BikeEmbedding rows get built in processGraph), so this is always human-readable,
    // never the embedding vector itself.
    private List<Map<String, Object>> toVectorChunkEntries(List<BikeEmbedding> matches) {
        return matches.stream()
                .map(m -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("sourceType", Objects.toString(m.getSourceType(), ""));
                    entry.put("name",       Objects.toString(m.getName(), ""));
                    entry.put("text",       Objects.toString(m.getTextContent(), ""));
                    return entry;
                })
                .collect(Collectors.toList());
    }

    // Runs a Cypher query and, when a trace collector is supplied, records the query text, its
    // parameters, and how many rows came back -- purely diagnostic (surfaced to the FE so a chat
    // answer can show exactly what was asked of the database), never affects retrieval itself.
    private List<Record> runTraced(Session session, String stage, String cypher,
                                    Map<String, Object> params, List<Map<String, Object>> cypherTrace) {
        List<Record> rows = session.run(cypher, params).list();
        if (cypherTrace != null) {
            cypherTrace.add(Map.of(
                    "stage",       stage,
                    "query",       cypher,
                    "params",      params,
                    "resultCount", rows.size()
            ));
        }
        return rows;
    }

    // graph mode: keyword-match node names in Neo4j, then expand up to maxHops hops out —
    // no PostgreSQL
    private List<Map<String, Object>> buildKeywordGraphContext(String question, String label,
                                                               int topK, int neighborLimit, int maxHops,
                                                               List<Map<String, Object>> cypherTrace) {
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
            runTraced(session, "keyword match + 1-hop expansion",
                    "MATCH (n:KGNode {sourceLabel: $label})-[r]-(nb:KGNode {sourceLabel: $label}) " +
                    "WHERE any(kw IN $keywords WHERE toLower(n.name) CONTAINS kw) " +
                    "RETURN n.name AS node, type(r) AS relType, nb.name AS neighborName, " +
                    "coalesce(nb.text, '') AS neighborText, coalesce(nb.description, '') AS neighborDesc " +
                    "ORDER BY CASE WHEN type(r) STARTS WITH 'HAS_' THEN 1 ELSE 0 END ASC, n.name, relType, neighborName " +
                    "LIMIT $limit",
                    Map.of("label", label, "keywords", keywords, "limit", topK * neighborLimit),
                    cypherTrace
            ).forEach(r -> {
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

            // Hops 2..maxHops from entity neighbors
            int perHopLimit = Math.max(3, neighborLimit / 4);
            expandFurtherHops(session, label, twoHopSeeds, expanded, maxHops, perHopLimit, context, cypherTrace);
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

    private List<Map<String, Object>> buildGraphContext(List<BikeEmbedding> matches, String label, int neighborLimit,
                                                         int maxHops, List<Map<String, Object>> cypherTrace) {
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
                    List<Record> parents = runTraced(session, "resolve parent entity for chunk \"" + match.getName() + "\"",
                            "MATCH (c:KGNode {name: $name, sourceLabel: $label})" +
                            "<-[:HAS_CHUNK]-(s:KGNode)<-[r]-(e:KGNode {sourceLabel: $label}) " +
                            "WHERE NOT e.nodeType IN ['section','chunk'] " +
                            "RETURN e.name AS entityName ORDER BY entityName LIMIT 1",
                            Map.of("name", match.getName(), "label", label),
                            cypherTrace
                    );
                    // CSV row passages have no chunk/section scaffolding -- the passage is named
                    // after its own row node, which is the seed itself.
                    if (parents.isEmpty()) {
                        parents = runTraced(session, "resolve row node for passage \"" + match.getName() + "\"",
                                "MATCH (e:KGNode {name: $name, sourceLabel: $label}) " +
                                "WHERE NOT coalesce(e.nodeType, '') IN ['section','chunk'] " +
                                "RETURN e.name AS entityName LIMIT 1",
                                Map.of("name", match.getName(), "label", label),
                                cypherTrace
                        );
                    }
                    parents.forEach(r -> {
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
                runTraced(session, "1-hop expansion from \"" + name + "\"",
                        "MATCH (n:KGNode {name: $name, sourceLabel: $label})-[r]-(nb:KGNode {sourceLabel: $label}) " +
                        "RETURN n.name AS node, type(r) AS relType, nb.name AS neighborName, " +
                        "coalesce(nb.text, '') AS neighborText, coalesce(nb.description, '') AS neighborDesc " +
                        "ORDER BY CASE WHEN type(r) STARTS WITH 'HAS_' THEN 1 ELSE 0 END ASC, n.name, relType, neighborName " +
                        "LIMIT $limit",
                        Map.of("name", name, "label", label, "limit", neighborLimit),
                        cypherTrace
                ).forEach(r -> {
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

        // Pass 3 — hops 2..maxHops expand (entity-to-entity only, smaller limit)
        int perHopLimit = Math.max(3, neighborLimit / 4);
        try (Session session = driver.session()) { // Neo4j
            expandFurtherHops(session, label, twoHopSeeds, expanded, maxHops, perHopLimit, context, cypherTrace);
        } catch (Exception e) {
            log.warn("Further-hop expansion failed: {}", e.getMessage());
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

    // Beyond the caller's own hop-1 query (which already produced `frontier`, the hop-1 neighbor
    // names), walks outward up to maxHops total: hop 2 expands from hop 1's neighbors, hop 3 from
    // hop 2's, and so on, re-tracing nothing already in `alreadyExpanded`. Every pass is
    // entity-to-entity only (HAS_*/HAS_CHUNK edges excluded) -- those connect structural
    // scaffolding (sections, chunks), not facts worth walking further from.
    private void expandFurtherHops(Session session, String label, Set<String> frontier,
                                   Set<String> alreadyExpanded, int maxHops, int perNodeLimit,
                                   List<Map<String, Object>> context, List<Map<String, Object>> cypherTrace) {
        for (int hop = 2; hop <= maxHops && !frontier.isEmpty(); hop++) {
            Set<String> nextFrontier = new LinkedHashSet<>();
            for (String name : frontier) {
                if (!alreadyExpanded.add(name)) continue;
                runTraced(session, hop + "-hop expansion from \"" + name + "\"",
                        "MATCH (n:KGNode {name: $name, sourceLabel: $label})-[r]-(nb:KGNode {sourceLabel: $label}) " +
                        "WHERE NOT type(r) STARTS WITH 'HAS_' AND NOT type(r) = 'HAS_CHUNK' " +
                        "RETURN n.name AS node, type(r) AS relType, nb.name AS neighborName, " +
                        "'' AS neighborText, coalesce(nb.description, '') AS neighborDesc " +
                        "ORDER BY CASE WHEN type(r) STARTS WITH 'IS_A' THEN 0 ELSE 1 END, relType, neighborName " +
                        "LIMIT $limit",
                        Map.of("name", name, "label", label, "limit", perNodeLimit),
                        cypherTrace
                ).forEach(r -> {
                    String neighborName = r.get("neighborName").asString("");
                    context.add(Map.of(
                            "node",         r.get("node").asString(""),
                            "relationship", r.get("relType").asString(""),
                            "neighbor",     neighborName,
                            "neighborText", "",
                            "neighborDesc", r.get("neighborDesc").asString("")
                    ));
                    if (!alreadyExpanded.contains(neighborName)) nextFrontier.add(neighborName);
                });
            }
            frontier = nextFrontier;
        }
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
