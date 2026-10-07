package com.moviesApp.repositories;

import com.moviesApp.entities.BikeEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface BikeEmbeddingRepository extends JpaRepository<BikeEmbedding, Long> {

    long countBySourceType(String sourceType);

    @Modifying
    @Transactional
    void deleteBySourceType(String sourceType);

    // pgvector cosine similarity via TEXT::vector cast.
    // Requires: CREATE EXTENSION IF NOT EXISTS vector; on your PostgreSQL instance.
    // Lower distance score = more similar.
    // Pass null for sourceType to search across all types.
    @Query(value =
        "SELECT * FROM bike_embeddings " +
        "WHERE (:sourceType IS NULL OR source_type = :sourceType) " +
        "ORDER BY (embedding_json::vector <=> CAST(:queryVector AS vector)) " +
        "LIMIT :k",
        nativeQuery = true)
    List<BikeEmbedding> findSimilar(
            @Param("queryVector") String queryVector,
            @Param("sourceType") String sourceType,
            @Param("k") int k
    );

    // Knowledge graph node similarity search filtered by label (entity names only)
    @Query(value =
        "SELECT * FROM bike_embeddings " +
        "WHERE source_type = 'knowledge_node' AND labels = :label " +
        "ORDER BY (embedding_json::vector <=> CAST(:queryVector AS vector)) " +
        "LIMIT :k",
        nativeQuery = true)
    List<BikeEmbedding> findSimilarByLabel(
            @Param("queryVector") String queryVector,
            @Param("label") String label,
            @Param("k") int k
    );

    // Search both entity names (knowledge_node) and chunk text (chunk_text) for a label
    @Query(value =
        "SELECT * FROM bike_embeddings " +
        "WHERE source_type IN ('knowledge_node', 'chunk_text') AND labels = :label " +
        "ORDER BY (embedding_json::vector <=> CAST(:queryVector AS vector)) " +
        "LIMIT :k",
        nativeQuery = true)
    List<BikeEmbedding> findSimilarAllTypesByLabel(
            @Param("queryVector") String queryVector,
            @Param("label") String label,
            @Param("k") int k
    );

    @Modifying
    @Transactional
    void deleteBySourceTypeAndLabels(String sourceType, String labels);

    // Delete all embeddings for a label regardless of source type
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM bike_embeddings WHERE labels = :labels", nativeQuery = true)
    void deleteAllByLabels(@Param("labels") String labels);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM bike_embeddings WHERE source_type = :sourceType AND labels = :labels AND name IN :names",
           nativeQuery = true)
    void deleteBySourceTypeAndLabelsAndNameIn(
            @Param("sourceType") String sourceType,
            @Param("labels") String labels,
            @Param("names") List<String> names);

    long countBySourceTypeAndLabels(String sourceType, String labels);

    // Unfiltered by source_type -- a label created purely via CSV import (knowledge_node-free,
    // only chunk_text rows) must still be countable/discoverable here.
    long countByLabels(String labels);

    // Unfiltered by source_type for the same reason: a label with only chunk_text rows (e.g.
    // CSV-only import, no suggest-graph/process-graph run) is still a real, chat-able knowledge
    // base and must show up in /knowledge/labels and /knowledge/status.
    @Query(value = "SELECT DISTINCT labels FROM bike_embeddings", nativeQuery = true)
    List<String> findDistinctKnowledgeLabels();
}
