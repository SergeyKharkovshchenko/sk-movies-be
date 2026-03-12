package com.moviesApp.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;

import org.springframework.stereotype.Service;

import com.moviesApp.entities.User;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class UserService {

        private final Neo4jService neo4j;

        public UserService(Neo4jService neo4j) {
                this.neo4j = neo4j;
        }

        public List<User> getAllUsers() {
                String query = "MATCH (u:User)<-[r:`USERS_NEO4J.CSV`]-(:Review) " +
                                "WITH u, count(r) AS numReviews " +
                                "ORDER BY numReviews DESC " +
                                "RETURN u, numReviews " +
                                "LIMIT 20";

                return neo4j.queryList(
                                query,
                                Map.of(),
                                r -> {
                                        Node userNode = r.get("u").asNode();
                                        User user = new User();
                                        user.setId(userNode.get("id").asString());
                                        user.setUserId(userNode.get("userId").asString());
                                        user.setNumberOfReviews(r.get("numReviews").asNumber());
                                        return user;
                                });
        }

        // public List<Map<String, Object>> createRatingsEmbedding() {
        // String query = "MATCH
        // (m:Movie)<-[REVIEWED_FROM_CSV]-(rv:Review)-[r:`USERS_NEO4J.CSV`]->(u:User ) "
        // +
        // "WHERE u.userId IN ['ur4445210'] " +
        // "WITH u, m, rv.rating as test " +
        // "ORDER BY m.movieTitle " +
        // "WITH u, collect(DISTINCT {movieId: m.movieId, title: m.movieTitle, rating:
        // test})[0..9] as topMovies "
        // +

        // "UNWIND topMovies as movieData " +

        // // ✅ 1. embedText с toString(rating)
        // "WITH u, movieData, " +
        // " ('User: ' + u.userId + ' | Movie: ' + movieData.title + ' | Rating: ' +
        // toString(movieData.rating)) AS embedText "
        // +

        // // ✅ 2. Ollama
        // "CALL
        // apoc.load.json('http://localhost:11434/api/embeddings?model=nomic-embed-text&prompt='
        // + " +
        // " apoc.text.urlencode(embedText)) " +
        // "YIELD value as ollamaResponse " +

        // // ✅ 3. WITH embedding (ЕДИНСТВЕННЫЙ раз!)
        // "WITH u, movieData, ollamaResponse.embedding AS embedding " +

        // // ✅ 4. Сохраняем
        // "MERGE (u)-[:HAS_MOVIE_EMBED]->(eme:UserMovieEmbedding { " +
        // " userId: u.userId, " +
        // " movieId: movieData.movieId " +
        // "}) " +
        // "SET eme.embedding = embedding, " + // ✅ embedding, НЕ ollamaResponse!
        // " eme.rating = movieData.rating, " +
        // " eme.movieTitle = movieData.title " +

        // "RETURN u.userId, eme.movieId, eme.movieTitle, eme.rating";

        // return neo4j.queryList(
        // query,
        // Map.of("userIds", List.of("ur4445210")),
        // r -> Map.of(
        // "userId", r.get("u.userId").asString(),
        // "movieId", r.get("eme.movieId").asString(),
        // "movieTitle", r.get("eme.movieTitle").asString(),
        // "rating", r.get("eme.rating").asDouble()));
        // }

        // public List<Map<String, Object>> ollamaTest() throws Exception {
        // String apiKey =
        // "jina_e18883450d33421f91cf8dddbf1e8d3caglHJmF96YdIHKQ8YayusXtyfmOA"; // ←
        // REPLACE with real key
        // // from jina.ai dashboard!

        // Map<String, Object> payload = Map.of(
        // "model", "jina-embeddings-v5-text-small",
        // "task", "retrieval.query",
        // "dimensions", 1024,
        // // "input", List.of("test"));
        // "input", "test");

        // String jsonBody = new ObjectMapper().writeValueAsString(payload);

        // HttpClient client = HttpClient.newHttpClient();
        // HttpRequest request = HttpRequest.newBuilder()
        // .uri(URI.create("https://api.jina.ai/v1/embeddings"))
        // .header("Authorization", "Bearer " + apiKey)
        // .header("Content-Type", "application/json")
        // .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        // .build();

        // HttpResponse<String> response = client.send(request,
        // HttpResponse.BodyHandlers.ofString());

        // if (response.statusCode() != 200) {
        // throw new RuntimeException("Jina API failed " + response.statusCode() + ": "
        // + response.body());
        // }

        // // Debug: Print raw response
        // System.out.println("Jina response: " + response.body());

        // // Map<String, Object> result = new ObjectMapper().readValue(response.body(),
        // // Map.class);

        // // // Safe parsing with null checks
        // // @SuppressWarnings("unchecked")
        // // List<Map<String, Object>> data = (List<Map<String, Object>>)
        // // result.get("data");
        // // if (data == null || data.isEmpty()) {
        // // throw new RuntimeException("No data in response: " + response.body());
        // // }

        // // Map<String, Object> firstItem = data.get(0);
        // // @SuppressWarnings("unchecked")
        // // List<Double> embedding = (List<Double>) firstItem.get("embedding");
        // // if (embedding == null || embedding.isEmpty()) {
        // // throw new RuntimeException("Empty embedding: " + firstItem);
        // // }

        // // String cypher = """
        // // CREATE (e:TestEmbedding {first3dims: $first3dims, dim: $dim})
        // // RETURN e
        // // """;

        // // Map<String, Object> params = Map.of(
        // // "first3dims", embedding.subList(0, Math.min(3, embedding.size())),
        // // "dim", embedding.size());

        // // return neo4j.queryList(cypher, params,
        // // r -> Map.of(
        // // "first3dims", r.get("e.first3dims").asList(v -> v.asDouble()),
        // // "dim", r.get("e.dim").asLong()));
        // }

        // private Object toParamValue(Object value) {
        // if (value instanceof Value v) {
        // if (v.isNull())
        // return null;
        // String typeName = v.type().name();
        // System.out.println("Converting value of type: " + typeName + " with value: "
        // + v);
        // return switch (typeName) {
        // case "STRING" -> v.asString();
        // case "NUMBER" -> v.asDouble();
        // case "LIST" -> v.asList(this::toParamValue);
        // default -> v.asObject();
        // };
        // }
        // return value;
        // }

        public List<Map<String, Object>> createRatingsEmbeddingSafe() throws Exception {

                try {

                        // Get top 10 movies for user first
                        String getMoviesQuery = """
                                        MATCH (m:Movie)<-[REVIEWED_FROM_CSV]-(rv:Review)-[r:`USERS_NEO4J.CSV`]->(u:User)
                                        WHERE u.userId IN ['ur4445210']
                                        RETURN u.userId AS userId,
                                               collect(DISTINCT {movieId: m.movieId, title: m.movieTitle, rating: rv.rating})[0..9] AS topMovies
                                        """;

                        List<Map<String, Object>> movieData = neo4j.queryList(getMoviesQuery,
                                        Map.of("userIds", List.of("ur4445210")),
                                        r -> Map.of(
                                                        "userId", r.get("userId").asString(),
                                                        "topMovies", r.get("topMovies").asList()));

                        String apiKey = "jina_e18883450d33421f91cf8dddbf1e8d3caglHJmF96YdIHKQ8YayusXtyfmOA";

                        HttpClient client = HttpClient.newHttpClient();

                        List<Map<String, Object>> results = new ArrayList<>();

                        for (Map<String, Object> userMovies : movieData) {
                                String userId = (String) userMovies.get("userId");
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> topMovies = (List<Map<String, Object>>) userMovies
                                                .get("topMovies");

                                System.out.println("Processing user: " + userId + " with movies: " + topMovies);

                                for (Map<String, Object> movie : topMovies) {
                                        String embedText = String.format("User: %s | Movie: %s | Rating: %s",
                                                        userId, movie.get("title"), movie.get("rating"));

                                        // Jina API call
                                        Map<String, Object> payload = Map.of(
                                                        "model", "jina-embeddings-v5-text-small",
                                                        "task", "retrieval.query",
                                                        "dimensions", 1024,
                                                        "input", List.of(embedText));

                                        String jsonBody = new ObjectMapper().writeValueAsString(payload);

                                        System.out.println("jsonBody: " + jsonBody);

                                        HttpRequest request = HttpRequest.newBuilder()
                                                        .uri(URI.create("https://api.jina.ai/v1/embeddings"))
                                                        .header("Authorization", "Bearer " + apiKey)
                                                        .header("Content-Type", "application/json")
                                                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                                                        .build();

                                        HttpResponse<String> response = client.send(request,
                                                        HttpResponse.BodyHandlers.ofString());

                                        if (response.statusCode() != 200) {
                                                throw new RuntimeException("Jina failed for " + embedText + ": " +
                                                                response.body());
                                        }

                                        Map<String, Object> result = new ObjectMapper().readValue(response.body(),
                                                        Map.class);
                                        @SuppressWarnings("unchecked")
                                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                                        @SuppressWarnings("unchecked")
                                        List<Double> embedding = (List<Double>) data.get(0).get("embedding");

                                        // Save to Neo4j
                                        String cypher = """
                                                        MERGE (u:User {userId: 'ur4445210' })
                                                        MERGE (u)-[:HAS_MOVIE_EMBED]->(eme:UserMovieEmbedding {
                                                        userId: 'ur4445210',
                                                        movieId: $movieId,
                                                        movieTitle: $movieTitle
                                                        })
                                                        SET eme.embedding = $embedding,
                                                        eme.rating = $rating,
                                                        eme.movieTitle = $movieTitle
                                                        RETURN u.userId, eme.movieId, eme.movieTitle, eme.rating
                                                        """;

                                        // System.out.println("movie: " + movie);

                                        Map<String, Object> params = Map.ofEntries(
                                                        Map.entry("userId", userId != null ? userId : "unknown"),
                                                        Map.entry("movieId", movie.getOrDefault("movieId", "unknown")),
                                                        Map.entry("movieTitle", movie.getOrDefault("title", "N/A")),
                                                        Map.entry("rating", movie.getOrDefault("rating", 0)),
                                                        Map.entry("embedding", embedding));

                                        // System.out.println("cypher: " + cypher);
                                        // System.out.println("params: " + params);
                                        // System.out.println("neoResult: " + neoResult);

                                        List<Map<String, Object>> neoResult = neo4j.queryList(cypher, params,
                                                        r -> Map.of(
                                                                        "userId", r.get("u.userId").asString(),
                                                                        "movieId", r.get("eme.movieId").asString(),
                                                                        "movieTitle",
                                                                        r.get("eme.movieTitle").asString(),
                                                                        "rating", r.get("eme.rating").asDouble()));

                                        System.out.println("neoResult: " + neoResult);

                                        results.addAll(neoResult);
                                }
                        }

                        return results;

                } catch (Exception e) {
                        // log.error("Embedding creation failed: {}", e.getMessage(), e);
                        return List.of(Map.of(
                                        "error", "Failed: " + e.getMessage(),
                                        "userId", "ur4445210"));
                }
        }

        public List<Map<String, Object>> createRatingsEmbeddingSafe2() throws Exception {
                try {
                        // Get top 10 movies for user first
                        String getMoviesQuery = """
                                        MATCH (m:Movie)<-[REVIEWED_FROM_CSV]-(rv:Review)-[r:`USERS_NEO4J.CSV`]->(u:User)
                                        WHERE u.userId IN $userIds
                                        RETURN u.userId AS userId,
                                               collect(DISTINCT {movieId: m.movieId, movieTitle: m.movieTitle, rating: rv.rating})[0..9] AS topMovies
                                        """;

                        List<Map<String, Object>> movieData = neo4j.queryList(getMoviesQuery,
                                        Map.of("userIds", List.of("ur4445210")),
                                        r -> Map.of(
                                                        "userId", r.get("userId").asString(),
                                                        "topMovies", r.get("topMovies").asList()));

                        String apiKey = "jina_e18883450d33421f91cf8dddbf1e8d3caglHJmF96YdIHKQ8YayusXtyfmOA";
                        HttpClient client = HttpClient.newHttpClient();

                        List<Map<String, Object>> results = new ArrayList<>();

                        for (Map<String, Object> userMovies : movieData) {
                                String userId = (String) userMovies.get("userId");
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> topMovies = (List<Map<String, Object>>) userMovies
                                                .get("topMovies");

                                System.out.println(
                                                "Processing user: " + userId + " with " + topMovies.size() + " movies");

                                // Collect ALL embeddings for this user
                                List<List<Double>> allMovieEmbeddings = new ArrayList<>();

                                for (Map<String, Object> movie : topMovies) {
                                        String embedText = String.format("User: %s | Movie: %s | Rating: %s",
                                                        userId, movie.get("movieTitle"), movie.get("rating"));

                                        // Jina API call
                                        Map<String, Object> payload = Map.of(
                                                        "model", "jina-embeddings-v5-text-small",
                                                        "task", "retrieval.query",
                                                        "dimensions", 1024,
                                                        "input", List.of(embedText));

                                        String jsonBody = new ObjectMapper().writeValueAsString(payload);
                                        HttpRequest request = HttpRequest.newBuilder()
                                                        .uri(URI.create("https://api.jina.ai/v1/embeddings"))
                                                        .header("Authorization", "Bearer " + apiKey)
                                                        .header("Content-Type", "application/json")
                                                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                                                        .build();

                                        HttpResponse<String> response = client.send(request,
                                                        HttpResponse.BodyHandlers.ofString());

                                        if (response.statusCode() != 200) {
                                                throw new RuntimeException("Jina failed for " + embedText + ": "
                                                                + response.body());
                                        }

                                        Map<String, Object> result = new ObjectMapper().readValue(response.body(),
                                                        Map.class);
                                        @SuppressWarnings("unchecked")
                                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                                        @SuppressWarnings("unchecked")
                                        List<Double> embedding = (List<Double>) data.get(0).get("embedding");

                                        allMovieEmbeddings.add(embedding);
                                        System.out.println("Got embedding for " + movie.get("movieTitle") + " dim="
                                                        + embedding.size());
                                }

                                // Compute average embedding (1024 dimensions)
                                List<Double> avgEmbedding = computeAverageEmbedding(allMovieEmbeddings);

                                // Create/update SINGLE user profile embedding
                                String profileCypher = """
                                                MERGE (u:User {userId: $userId})
                                                MERGE (u)-[:HAS_PROFILE_EMBED]->(upe:UserProfileEmbedding {userId: $userId})
                                                SET upe.profileEmbedding = $avgEmbedding,
                                                    upe.movieCount = size($movieEmbeddings)
                                                RETURN u.userId AS userId,
                                                       size(upe.profileEmbedding) AS embeddingDim,
                                                       upe.movieCount AS movieCount
                                                """;

                                Map<String, Object> profileParams = Map.of(
                                                "userId", userId,
                                                "avgEmbedding", avgEmbedding,
                                                "movieEmbeddings", allMovieEmbeddings // For movieCount
                                );

                                List<Map<String, Object>> profileResult = neo4j.queryList(profileCypher, profileParams,
                                                r -> Map.of(
                                                                "userId", r.get("userId").asString(),
                                                                "embeddingDim", r.get("embeddingDim").asLong(),
                                                                "movieCount", r.get("movieCount").asLong()));

                                System.out.println("Created profile for " + userId + ": " + profileResult);
                                results.addAll(profileResult);
                        }

                        return results;

                } catch (Exception e) {
                        return List.of(Map.of(
                                        "error", "Failed: " + e.getMessage(),
                                        "userId", "ur4445210"));
                }
        }

        public List<Map<String, Object>> createRatingsEmbeddingSafe3(String userId) throws Exception {

                try {
                        // Replace hardcoded userIds with single userId
                        String getMoviesQuery = """
                                        MATCH (m:Movie)<-[REVIEWED_FROM_CSV]-(rv:Review)-[r:`USERS_NEO4J.CSV`]->(u:User)
                                        WHERE u.userId = $userId
                                        RETURN u.userId AS userId,
                                               collect(DISTINCT {movieId: m.movieId, movieTitle: m.movieTitle, rating: rv.rating})[0..9] AS topMovies
                                        """;

                        List<Map<String, Object>> movieData = neo4j.queryList(getMoviesQuery,
                                        Map.of("userId", userId), // Single userId parameter
                                        r -> Map.of(
                                                        "userId", r.get("userId").asString(),
                                                        "topMovies", r.get("topMovies").asList()));

                        String apiKey = "jina_e18883450d33421f91cf8dddbf1e8d3caglHJmF96YdIHKQ8YayusXtyfmOA";
                        HttpClient client = HttpClient.newHttpClient();

                        List<Map<String, Object>> results = new ArrayList<>();

                        for (Map<String, Object> userMovies : movieData) {
                                String userIdNumber = (String) userMovies.get("userId");
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> topMovies = (List<Map<String, Object>>) userMovies
                                                .get("topMovies");

                                System.out.println(
                                                "Processing user: " + userIdNumber + " with " + topMovies.size()
                                                                + " movies");

                                // Collect ALL embeddings for this user
                                List<List<Double>> allMovieEmbeddings = new ArrayList<>();

                                for (Map<String, Object> movie : topMovies) {
                                        String embedText = String.format("User: %s | Movie: %s | Rating: %s",
                                                        userIdNumber, movie.get("movieTitle"), movie.get("rating"));

                                        // Jina API call
                                        Map<String, Object> payload = Map.of(
                                                        "model", "jina-embeddings-v5-text-small",
                                                        "task", "retrieval.query",
                                                        "dimensions", 1024,
                                                        "input", List.of(embedText));

                                        String jsonBody = new ObjectMapper().writeValueAsString(payload);
                                        HttpRequest request = HttpRequest.newBuilder()
                                                        .uri(URI.create("https://api.jina.ai/v1/embeddings"))
                                                        .header("Authorization", "Bearer " + apiKey)
                                                        .header("Content-Type", "application/json")
                                                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                                                        .build();

                                        HttpResponse<String> response = client.send(request,
                                                        HttpResponse.BodyHandlers.ofString());

                                        if (response.statusCode() != 200) {
                                                throw new RuntimeException("Jina failed for " + embedText + ": "
                                                                + response.body());
                                        }

                                        Map<String, Object> result = new ObjectMapper().readValue(response.body(),
                                                        Map.class);
                                        @SuppressWarnings("unchecked")
                                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                                        @SuppressWarnings("unchecked")
                                        List<Double> embedding = (List<Double>) data.get(0).get("embedding");

                                        allMovieEmbeddings.add(embedding);
                                        System.out.println("Got embedding for " + movie.get("movieTitle") + " dim="
                                                        + embedding.size());
                                }

                                // Compute average embedding (1024 dimensions)
                                List<Double> avgEmbedding = computeAverageEmbedding(allMovieEmbeddings);

                                // Create/update SINGLE user profile embedding
                                String profileCypher = """
                                                MERGE (u:User {userId: $userId})
                                                MERGE (u)-[:HAS_PROFILE_EMBED]->(upe:UserProfileEmbedding {userId: $userId})
                                                SET upe.profileEmbedding = $avgEmbedding,
                                                    upe.movieCount = size($movieEmbeddings)
                                                RETURN u.userId AS userId,
                                                       size(upe.profileEmbedding) AS embeddingDim,
                                                       upe.movieCount AS movieCount
                                                """;

                                Map<String, Object> profileParams = Map.of(
                                                "userId", userIdNumber,
                                                "avgEmbedding", avgEmbedding,
                                                "movieEmbeddings", allMovieEmbeddings // For movieCount
                                );

                                List<Map<String, Object>> profileResult = neo4j.queryList(profileCypher, profileParams,
                                                r -> Map.of(
                                                                "userId", r.get("userId").asString(),
                                                                "embeddingDim", r.get("embeddingDim").asLong(),
                                                                "movieCount", r.get("movieCount").asLong()));

                                System.out.println("Created profile for " + userIdNumber + ": " + profileResult);
                                results.addAll(profileResult);
                        }

                        return results;

                } catch (Exception e) {
                        return List.of(Map.of(
                                        "error", "Failed: " + e.getMessage(),
                                        "userId", userId // Pass through userId
                        ));
                }
        }

        private List<Double> computeAverageEmbedding(List<List<Double>> embeddings) {
                if (embeddings.isEmpty())
                        return List.of();

                int dim = embeddings.get(0).size(); // 1024
                List<Double> avg = new ArrayList<>(Collections.nCopies(dim, 0.0));

                for (List<Double> emb : embeddings) {
                        for (int i = 0; i < dim; i++) {
                                avg.set(i, avg.get(i) + emb.get(i));
                        }
                }

                // Divide by count
                for (int i = 0; i < dim; i++) {
                        avg.set(i, avg.get(i) / embeddings.size());
                }
                return avg;
        }

        public List<Map<String, Object>> getSimilarUsers(String targetUserId, int limit) {
                // String cypher = """
                // MATCH (u:User {userId:
                // $targetUserId})-[:HAS_PROFILE_EMBED]->(up:UserProfileEmbedding)
                // WITH up.profileEmbedding AS givenEmbedding
                // MATCH (otherUser:User)-[:HAS_PROFILE_EMBED]->(otherUp:UserProfileEmbedding)
                // WHERE otherUp.profileEmbedding IS NOT NULL
                // AND otherUser.userId <> $targetUserId
                // WITH otherUser, otherUp.profileEmbedding AS otherEmbedding, givenEmbedding
                // WITH otherUser, gds.similarity.cosine(givenEmbedding, otherEmbedding) AS
                // similarity
                // ORDER BY similarity DESC
                // RETURN otherUser.userId AS userId,
                // similarity,
                // rank() OVER (ORDER BY similarity DESC) + 1 AS rank
                // LIMIT $limit
                // """;

                String cypher = """
                                MATCH (u:User {userId: $targetUserId})-[:HAS_PROFILE_EMBED]->(up:UserProfileEmbedding)
                                WITH up.profileEmbedding AS givenEmbedding
                                MATCH (otherUser:User)-[:HAS_PROFILE_EMBED]->(otherUp:UserProfileEmbedding)
                                WHERE otherUp.profileEmbedding IS NOT NULL
                                  AND otherUser.userId <> $targetUserId
                                WITH otherUser.userId AS userId,
                                     gds.similarity.cosine(givenEmbedding, otherUp.profileEmbedding) AS similarity
                                ORDER BY similarity DESC
                                WITH collect(userId)[0..$limit-1] AS topUserIds,
                                     collect(similarity)[0..$limit-1] AS topSimilarities
                                UNWIND range(0, size(topUserIds)-1) AS idx
                                RETURN topUserIds[idx] AS userId,
                                       topSimilarities[idx] AS similarity,
                                       idx + 1 AS rank
                                ORDER BY rank ASC
                                """;

                Map<String, Object> params = Map.of(
                                "targetUserId", targetUserId,
                                "limit", limit);

                return neo4j.queryList(cypher, params,
                                r -> Map.of(
                                                "userId", r.get("userId").asString(),
                                                "similarity", r.get("similarity").asDouble(),
                                                "rank", r.get("rank").asLong()));
        }

}