package com.moviesApp.entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "bike_embeddings", indexes = {
        @Index(name = "idx_bike_embed_source_type", columnList = "source_type"),
        @Index(name = "idx_bike_embed_name",        columnList = "name")
})
public class BikeEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // "node" or "relationship"
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    // e.g. "brand-cube", "line-road_HAS_SERIES_series-agree"
    @Column(name = "node_id", length = 300)
    private String nodeId;

    @Column(name = "name", length = 300)
    private String name;

    // Neo4j labels or relationship type
    @Column(name = "labels", length = 200)
    private String labels;

    // The text that was actually sent to the embedding model
    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    // Stored as "[f1,f2,...]" — compatible with pgvector's TEXT::vector cast.
    // To enable fast ANN search, run on your PostgreSQL instance:
    //   CREATE EXTENSION IF NOT EXISTS vector;
    // Then migrate this column and add an HNSW index:
    //   ALTER TABLE bike_embeddings ADD COLUMN embedding_vector vector(1024);
    //   UPDATE bike_embeddings SET embedding_vector = embedding_json::vector;
    //   CREATE INDEX ON bike_embeddings USING hnsw (embedding_vector vector_cosine_ops);
    @Column(name = "embedding_json", columnDefinition = "TEXT", nullable = false)
    private String embeddingJson;

    @Column(name = "embedder", length = 50, nullable = false)
    private String embedder;

    @Column(name = "dimension")
    private Integer dimension;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public BikeEmbedding() {}

    public BikeEmbedding(String sourceType, String nodeId, String name, String labels,
                         String textContent, String embeddingJson, String embedder, int dimension) {
        this.sourceType   = sourceType;
        this.nodeId       = nodeId;
        this.name         = name;
        this.labels       = labels;
        this.textContent  = textContent;
        this.embeddingJson = embeddingJson;
        this.embedder     = embedder;
        this.dimension    = dimension;
        this.createdAt    = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLabels() { return labels; }
    public void setLabels(String labels) { this.labels = labels; }
    public String getTextContent() { return textContent; }
    public void setTextContent(String textContent) { this.textContent = textContent; }
    public String getEmbeddingJson() { return embeddingJson; }
    public void setEmbeddingJson(String embeddingJson) { this.embeddingJson = embeddingJson; }
    public String getEmbedder() { return embedder; }
    public void setEmbedder(String embedder) { this.embedder = embedder; }
    public Integer getDimension() { return dimension; }
    public void setDimension(Integer dimension) { this.dimension = dimension; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
