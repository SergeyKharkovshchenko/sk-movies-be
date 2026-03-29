package com.moviesApp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "movie_poster_embeddings")
public class MoviePosterEmbedding {

    @Id
    private String movieId;

    @Column(nullable = false)
    private String posterUrl;

    @Column
    private String movieTitle;

    @Column(columnDefinition = "text", nullable = false)
    private String embeddingJson;

    @Column(columnDefinition = "text")
    private String imageBase64;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public MoviePosterEmbedding() {}

    public MoviePosterEmbedding(String movieId, String posterUrl, String embeddingJson, String imageBase64, LocalDateTime createdAt) {
        this.movieId = movieId;
        this.posterUrl = posterUrl;
        this.embeddingJson = embeddingJson;
        this.imageBase64 = imageBase64;
        this.createdAt = createdAt;
    }

    public String getMovieId() { return movieId; }
    public void setMovieId(String movieId) { this.movieId = movieId; }

    public String getPosterUrl() { return posterUrl; }
    public void setPosterUrl(String posterUrl) { this.posterUrl = posterUrl; }

    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }

    public String getEmbeddingJson() { return embeddingJson; }
    public void setEmbeddingJson(String embeddingJson) { this.embeddingJson = embeddingJson; }

    public String getImageBase64() { return imageBase64; }
    public void setImageBase64(String imageBase64) { this.imageBase64 = imageBase64; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
