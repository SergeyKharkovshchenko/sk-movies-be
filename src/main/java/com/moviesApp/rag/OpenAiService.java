package com.moviesApp.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiService {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    @Value("${OPENAI_API_KEY}")
    private String apiKey;

    /** Complex structured outputs — suggestGraph, RAG chat. Override via OPENAI_MODEL_DESIGN env var. */
    @Value("${openai.model.design:gpt-4o-mini}")
    private String designModel;

    /** High-volume simple tasks — suggestSections, triple extraction. Override via OPENAI_MODEL_EXTRACT env var. */
    @Value("${openai.model.extract:gpt-4.1-nano}")
    private String extractModel;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public OpenAiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String chat(String model, double temperature, int maxTokens, List<Map<String, String>> messages) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("messages", messages);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("OpenAI API error " + resp.statusCode() + ": " + resp.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(resp.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }

    /** suggestGraph — designModel, caller supplies token budget. */
    public String chatDesign(String systemPrompt, String userContent, int maxTokens) throws Exception {
        return chat(designModel, 0.2, maxTokens, List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userContent)
        ));
    }

    /** RAG chat with full message history — designModel, caller supplies temperature and token budget. */
    public String chatDesign(double temperature, int maxTokens, List<Map<String, String>> messages) throws Exception {
        return chat(designModel, temperature, maxTokens, messages);
    }

    /** Triple extraction per chunk — extractModel, 2000 token default. */
    public String chatExtract(String systemPrompt, String userContent) throws Exception {
        return chat(extractModel, 0.2, 2000, List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userContent)
        ));
    }

    /** suggestSections — extractModel, large token budget for verbatim text output. */
    public String chatExtract(String systemPrompt, String userContent, int maxTokens) throws Exception {
        return chat(extractModel, 0.2, maxTokens, List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userContent)
        ));
    }

    // Kept for EntityExtractorService compatibility — routes to extractModel at 2000 tokens
    public String chat(String systemPrompt, String userContent) throws Exception {
        return chatExtract(systemPrompt, userContent);
    }
}
