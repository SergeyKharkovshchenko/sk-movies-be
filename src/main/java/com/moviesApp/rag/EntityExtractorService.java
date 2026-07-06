package com.moviesApp.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class EntityExtractorService {

    private static final String SYSTEM_PROMPT = """
            Analyze the provided text and return a JSON object with two fields:

            1. "triples": an array of subject-predicate-object relationships.
               Format: [{"subject": "EntityName", "predicate": "RELATIONSHIP_TYPE", "object": "EntityName"}]
               Rules for triples:
               - Use concise entity names (1-5 words, preserve proper nouns exactly)
               - Use UPPERCASE_SNAKE_CASE predicates
               - Common predicates: BORN_IN, LED, FOUGHT_AT, PART_OF, MARRIED_TO, DIED_IN, RULED, ALLIED_WITH,
                 DEFEATED, COMMANDED, PARTICIPATED_IN, LOCATED_IN, FOUNDED, SUCCEEDED, PRECEDED
               - IMPORTANT — always extract causal/reason relationships when present:
                 CAUSED, REASON_FOR, LED_TO, RESULTED_IN, TRIGGERED, ENABLED, PREVENTED
               - Only include clear factual relationships stated in the text
               - If no relationships exist, use an empty array: []

            2. "descriptions": a map of entity name to a one-sentence description.
               Include every entity that appears in the triples.
               Format: {"EntityName": "One sentence describing what this entity is."}

            Return ONLY the JSON object — no markdown, no explanation:
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

    public record ExtractionResult(List<Triple> triples, Map<String, String> descriptions) {
        public static ExtractionResult empty() {
            return new ExtractionResult(List.of(), Map.of());
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
            String response = openAi.chat(prompt, text).strip();
            if (response.startsWith("```")) {
                response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(response, Map.class);

            // Parse triples
            @SuppressWarnings("unchecked")
            List<Map<String, String>> rawTriples = raw.containsKey("triples")
                    ? (List<Map<String, String>>) raw.get("triples")
                    : List.of();

            List<Triple> triples = rawTriples.stream()
                    .filter(m -> m.containsKey("subject") && m.containsKey("predicate") && m.containsKey("object"))
                    .map(m -> new Triple(
                            m.get("subject").strip(),
                            m.get("predicate").strip().toUpperCase().replaceAll("[^A-Z0-9]", "_").replaceAll("_{2,}", "_"),
                            m.get("object").strip()
                    ))
                    .filter(t -> !t.subject().isEmpty() && !t.predicate().isEmpty() && !t.object().isEmpty())
                    .toList();

            // Parse descriptions
            @SuppressWarnings("unchecked")
            Map<String, String> descriptions = raw.containsKey("descriptions")
                    ? (Map<String, String>) raw.get("descriptions")
                    : Map.of();

            return new ExtractionResult(triples, descriptions);

        } catch (Exception e) {
            return ExtractionResult.empty();
        }
    }
}
