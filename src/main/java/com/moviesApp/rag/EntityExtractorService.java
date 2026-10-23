package com.moviesApp.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EntityExtractorService {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractorService.class);

    private static final String SYSTEM_PROMPT = """
            Extract a knowledge graph from the provided text and return a JSON object with two fields.

            CLOSED WORLD -- the text is the only source of truth:
            - Use ONLY what the text itself states. Never add anything from your own knowledge, even
              if it is true in the real world (e.g. other films an actor appeared in, a person's
              birthplace, a well-known fact about a famous entity).
            - Do not infer, guess, or "complete" connections the text does not state. If two entities
              both appear in the text but no sentence connects them, there is no relationship.

            1. "triples": an array of subject-predicate-object relationships stated in the text.
               Format: [{"subject": "EntityName", "predicate": "RELATIONSHIP_TYPE", "object": "EntityName",
                         "evidence": "The exact sentence from the text that states it."}]
               Rules for triples:
               - "evidence" MUST be ONE sentence copied verbatim from the text, and it must name both
                 the subject and the object. A triple without such a sentence must not be emitted.
               - Use concise entity names (1-5 words), spelled exactly as in the text
               - Use UPPERCASE_SNAKE_CASE predicates
               - Common predicates: BORN_IN, LED, FOUGHT_AT, PART_OF, MARRIED_TO, DIED_IN, RULED, ALLIED_WITH,
                 DEFEATED, COMMANDED, PARTICIPATED_IN, LOCATED_IN, FOUNDED, SUCCEEDED, PRECEDED
               - Extract causal/reason relationships (CAUSED, REASON_FOR, LED_TO, RESULTED_IN, TRIGGERED,
                 ENABLED, PREVENTED) only when the text states them outright
               - If no relationships exist, use an empty array: []

            2. "descriptions": a map of entity name to ONE sentence copied verbatim from the text that
               names the entity and says the most about it. Never write a description yourself --
               only quote. Include every entity that appears in the triples.
               Format: {"EntityName": "The exact sentence from the text."}

            Return ONLY the JSON object -- no markdown, no explanation:
            {
              "triples": [...],
              "descriptions": {"EntityName": "description", ...}
            }
            """;

    private final OpenAiService openAi;
    private final ObjectMapper objectMapper;

    public EntityExtractorService(OpenAiService openAi, ObjectMapper objectMapper) {
        this.openAi = openAi;
        this.objectMapper = objectMapper;
    }

    public record Triple(String subject, String predicate, String object) {}

    /** {@code dropped}: triples the LLM proposed that failed the evidence check, with the reason. */
    public record ExtractionResult(List<Triple> triples, Map<String, String> descriptions, List<String> dropped) {
        public static ExtractionResult empty() {
            return new ExtractionResult(List.of(), Map.of(), List.of());
        }
    }

    public ExtractionResult extract(String text) {
        return extract(text, List.of());
    }

    public ExtractionResult extract(String text, List<String> canonicalNames) {
        String prompt = canonicalNames.isEmpty() ? SYSTEM_PROMPT : SYSTEM_PROMPT +
                "\nCANONICAL ENTITY NAMES — when any of these entities appear in the text, " +
                "use this EXACT spelling (do not paraphrase, abbreviate, or expand):\n" +
                String.join(", ", canonicalNames);
        try {
            // Every triple and description carries a quoted sentence, so a dense 1500-char chunk
            // easily needs more than the 2000-token default -- a cut-off reply is unparseable JSON
            // and the whole chunk would yield nothing.
            String response = openAi.chatExtract(prompt, text, 8000).strip();
            if (response.startsWith("```")) {
                response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(response, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, String>> rawTriples = raw.get("triples") instanceof List<?> l
                    ? (List<Map<String, String>>) l
                    : List.of();

            // Closed world, enforced: keep a triple only if its quoted sentence is really in this
            // chunk and names both entities -- otherwise it came from the model, not the text.
            List<Triple> triples = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (Map<String, String> m : rawTriples) {
                String s = m.get("subject"), p = m.get("predicate"), o = m.get("object");
                if (s == null || p == null || o == null) continue;
                Triple t = new Triple(s.strip(),
                        p.strip().toUpperCase().replaceAll("[^A-Z0-9]", "_").replaceAll("_{2,}", "_"),
                        o.strip());
                if (t.subject().isEmpty() || t.predicate().isEmpty() || t.object().isEmpty()) continue;
                String reason = EvidenceCheck.reject(text, m.get("evidence"), t.subject(), t.object());
                if (reason == null) {
                    triples.add(t);
                } else {
                    dropped.add(t.subject() + " -" + t.predicate() + "-> " + t.object() + " (" + reason + ")");
                }
            }

            // Descriptions are quotes, not prose: kept only for entities that survived and only when
            // the sentence is really in this chunk and names the entity. A model-written description
            // is exactly where outside knowledge ("... known for The Dark Knight Rises") would slip
            // back into the chat context.
            Set<String> kept = new LinkedHashSet<>();
            triples.forEach(t -> { kept.add(t.subject()); kept.add(t.object()); });
            Map<String, String> descriptions = new LinkedHashMap<>();
            if (raw.get("descriptions") instanceof Map<?, ?> d) {
                d.forEach((k, v) -> {
                    if (k instanceof String name && v instanceof String desc && kept.contains(name)) {
                        String reason = EvidenceCheck.reject(text, desc, name, name);
                        if (reason == null) descriptions.put(name, desc.strip());
                        else dropped.add("description of " + name + " (" + reason + ")");
                    }
                });
            }

            if (!dropped.isEmpty()) log.info("[extract] dropped {} unsupported item(s): {}", dropped.size(), dropped);
            return new ExtractionResult(triples, descriptions, dropped);

        } catch (Exception e) {
            // Was silent: a chunk that failed looked exactly like a chunk with no facts in it.
            log.warn("[extract] extraction failed for a {}-char chunk: {}", text.length(), e.getMessage());
            return ExtractionResult.empty();
        }
    }
}
