package com.moviesApp.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class EntityExtractorService {

    private static final String SYSTEM_PROMPT = """
            Extract entities and relationships from the provided text as JSON triples.
            Return ONLY a valid JSON array with this exact format:
            [{"subject": "EntityName", "predicate": "RELATIONSHIP_TYPE", "object": "EntityName"}]
            Rules:
            - Use concise entity names (1-4 words, preserve proper nouns exactly)
            - Use UPPERCASE_SNAKE_CASE predicates (e.g., BORN_IN, LED, FOUGHT_AT, PART_OF, MARRIED_TO, DIED_IN)
            - Only include clear factual relationships from the text
            - No markdown, no explanation — just the JSON array
            If no relationships are found, return an empty array: []
            """;

    private final OpenAiService openAi;
    private final ObjectMapper objectMapper;

    public EntityExtractorService(OpenAiService openAi, ObjectMapper objectMapper) {
        this.openAi = openAi;
        this.objectMapper = objectMapper;
    }

    public record Triple(String subject, String predicate, String object) {}

    public List<Triple> extract(String text) {
        try {
            String response = openAi.chat(SYSTEM_PROMPT, text).strip();
            // Strip markdown code fences GPT sometimes adds
            if (response.startsWith("```")) {
                response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, String>> raw = objectMapper.readValue(response,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            return raw.stream()
                    .filter(m -> m.containsKey("subject") && m.containsKey("predicate") && m.containsKey("object"))
                    .map(m -> new Triple(
                            m.get("subject").strip(),
                            m.get("predicate").strip().toUpperCase().replaceAll("[^A-Z0-9]", "_").replaceAll("_{2,}", "_"),
                            m.get("object").strip()
                    ))
                    .filter(t -> !t.subject().isEmpty() && !t.predicate().isEmpty() && !t.object().isEmpty())
                    .toList();
        } catch (Exception e) {
            // Don't fail the pipeline for one bad chunk
            return List.of();
        }
    }
}
