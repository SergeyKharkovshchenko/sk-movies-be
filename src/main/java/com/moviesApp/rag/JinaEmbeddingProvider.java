package com.moviesApp.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class JinaEmbeddingProvider implements EmbeddingProvider {

    private static final String JINA_URL = "https://api.jina.ai/v1/embeddings";
    // jina-embeddings-v3 produces 1024-dim vectors
    private static final String MODEL = "jina-embeddings-v3";

    @Value("${JINA_API_KEY}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String getId() {
        return "jina";
    }

    @Override
    public List<float[]> embed(List<String> texts) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("model", MODEL, "input", texts));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(JINA_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Jina API error " + response.statusCode() + ": " + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) parsed.get("data");

        List<float[]> result = new ArrayList<>();
        for (Map<String, Object> item : data) {
            @SuppressWarnings("unchecked")
            List<Number> embedding = (List<Number>) item.get("embedding");
            float[] floats = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                floats[i] = embedding.get(i).floatValue();
            }
            result.add(floats);
        }
        return result;
    }
}
