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

    /** Content plus the token usage OpenAI reported for that one call. */
    public record ChatResult(String content, int promptTokens, int completionTokens, int totalTokens) {}

    public String chat(String model, double temperature, int maxTokens, List<Map<String, String>> messages) throws Exception {
        return chatWithUsage(model, temperature, maxTokens, messages).content();
    }

    // Single place that actually calls the API and parses the response -- chat(...) above and
    // every *WithUsage overload below delegate here so there's only one HTTP/parsing path to keep
    // in sync with OpenAI's response shape.
    private ChatResult chatWithUsage(String model, double temperature, int maxTokens, List<Map<String, String>> messages) throws Exception {
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
        String content = (String) message.get("content");

        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) parsed.get("usage");
        int promptTokens     = usage != null ? ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue() : 0;
        int completionTokens = usage != null ? ((Number) usage.getOrDefault("completion_tokens", 0)).intValue() : 0;
        int totalTokens      = usage != null ? ((Number) usage.getOrDefault("total_tokens", 0)).intValue() : 0;
        return new ChatResult(content, promptTokens, completionTokens, totalTokens);
    }

    /** suggestGraph — designModel, caller supplies token budget. */
    public String chatDesign(String systemPrompt, String userContent, int maxTokens) throws Exception {
        return chat(designModel, 0.2, maxTokens, List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userContent)
        ));
    }

    /** Same call as chatDesign(String, String, int), but with token usage -- used where the
     *  caller surfaces per-call usage to the FE (e.g. the grounding check). */
    public ChatResult chatDesignWithUsage(String systemPrompt, String userContent, int maxTokens) throws Exception {
        return chatWithUsage(designModel, 0.2, maxTokens, List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userContent)
        ));
    }

    /** RAG chat with full message history — designModel, caller supplies temperature and token budget. */
    public String chatDesign(double temperature, int maxTokens, List<Map<String, String>> messages) throws Exception {
        return chat(designModel, temperature, maxTokens, messages);
    }

    /** Same call as chatDesign(double, int, List), but with token usage -- used by the main RAG
     *  answer call so its usage can be surfaced to the FE. */
    public ChatResult chatDesignWithUsage(double temperature, int maxTokens, List<Map<String, String>> messages) throws Exception {
        return chatWithUsage(designModel, temperature, maxTokens, messages);
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
