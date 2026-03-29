package com.moviesApp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesApp.entities.MoviePosterEmbedding;
import com.moviesApp.repositories.MoviePosterEmbeddingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PosterEmbeddingService {

    private final MoviePosterEmbeddingRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${omdb.api.key}")
    private String omdbApiKey;

    @Value("${JINA_API_KEY}")
    private String jinaApiKey;

    public PosterEmbeddingService(MoviePosterEmbeddingRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> getOrCreatePosterEmbedding(String movieId) throws Exception {
        if (movieId == null || movieId.isBlank()) {
            throw new IllegalArgumentException("movieId must not be blank");
        }
        Optional<MoviePosterEmbedding> existing = repository.findById(movieId);
        if (existing.isPresent() && existing.get().getImageBase64() != null) {
            MoviePosterEmbedding record = existing.get();
            return Map.of(
                    "movieId", record.getMovieId(),
                    "posterUrl", record.getPosterUrl(),
                    "embeddingDim", embeddingDimFromJson(record.getEmbeddingJson()),
                    "cached", true);
        }

        String[] movieInfo = existing.isPresent()
                ? new String[]{existing.get().getPosterUrl(), existing.get().getMovieTitle()}
                : fetchMovieInfo(movieId);
        String posterUrl = movieInfo[0];
        String movieTitle = movieInfo[1];
        String base64Image = downloadImageAsBase64(posterUrl);
        List<Double> embedding = embedImage(base64Image);
        String embeddingJson = objectMapper.writeValueAsString(embedding);

        MoviePosterEmbedding record = existing.orElse(new MoviePosterEmbedding());
        record.setMovieId(movieId);
        record.setPosterUrl(posterUrl);
        record.setMovieTitle(movieTitle);
        record.setEmbeddingJson(embeddingJson);
        record.setImageBase64(base64Image);
        record.setCreatedAt(LocalDateTime.now());
        repository.save(record);

        return Map.of(
                "movieId", movieId,
                "movieTitle", movieTitle != null ? movieTitle : "",
                "posterUrl", posterUrl,
                "embeddingDim", embedding.size(),
                "cached", false);
    }

    // returns [posterUrl, movieTitle]
    private String[] fetchMovieInfo(String movieId) throws Exception {
        String url = "https://www.omdbapi.com/?i=" + movieId + "&apikey=" + omdbApiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OMDB API failed " + response.statusCode() + ": " + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);

        if (!"True".equals(body.get("Response"))) {
            throw new RuntimeException("OMDB returned error for movieId=" + movieId + ": " + body.get("Error"));
        }

        String posterUrl = (String) body.get("Poster");
        if (posterUrl == null || posterUrl.equals("N/A")) {
            throw new RuntimeException("No poster available for movieId=" + movieId);
        }

        String title = (String) body.get("Title");
        return new String[]{posterUrl, title};
    }

    private String downloadImageAsBase64(String imageUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to download poster image " + response.statusCode() + " from " + imageUrl);
        }

        return Base64.getEncoder().encodeToString(response.body());
    }

    private List<Double> embedImage(String base64Image) throws Exception {
        Map<String, Object> payload = Map.of(
                "model", "jina-clip-v2",
                "input", List.of(Map.of("image", base64Image)));

        String jsonBody = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.jina.ai/v1/embeddings"))
                .header("Authorization", "Bearer " + jinaApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Jina API failed " + response.statusCode() + ": " + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        @SuppressWarnings("unchecked")
        List<Double> embedding = (List<Double>) data.get(0).get("embedding");
        return embedding;
    }

    public Map<String, Object> getPosterInfo(String movieId) {
        return repository.findById(movieId)
                .filter(r -> r.getImageBase64() != null)
                .map(r -> Map.<String, Object>of(
                        "movieId", r.getMovieId(),
                        "imageBase64", r.getImageBase64(),
                        "createdAt", r.getCreatedAt().toString()))
                .orElse(null);
    }

    public List<Map<String, Object>> getPosterSimilarities(String movieId) throws Exception {
        MoviePosterEmbedding target = repository.findById(movieId)
                .filter(r -> r.getEmbeddingJson() != null)
                .orElseThrow(() -> new IllegalArgumentException("No embedding found for movieId=" + movieId));

        List<Double> targetEmbedding = parseEmbedding(target.getEmbeddingJson());

        List<MoviePosterEmbedding> others = repository.findAll().stream()
                .filter(r -> !r.getMovieId().equals(movieId) && r.getEmbeddingJson() != null)
                .toList();

        List<Map<String, Object>> results = new ArrayList<>();
        for (MoviePosterEmbedding other : others) {
            List<Double> otherEmbedding = parseEmbedding(other.getEmbeddingJson());
            double similarity = cosineSimilarity(targetEmbedding, otherEmbedding);
            results.add(Map.of(
                    "movieId", other.getMovieId(),
                    "movieTitle", other.getMovieTitle() != null ? other.getMovieTitle() : "",
                    "similarity", similarity));
        }

        results.sort(Comparator.comparingDouble(m -> -((Double) m.get("similarity"))));
        return results;
    }

    private List<Double> parseEmbedding(String json) throws Exception {
        @SuppressWarnings("unchecked")
        List<Double> embedding = objectMapper.readValue(json, List.class);
        return embedding;
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private int embeddingDimFromJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            List<Double> embedding = objectMapper.readValue(json, List.class);
            return embedding.size();
        } catch (Exception e) {
            return -1;
        }
    }
}
