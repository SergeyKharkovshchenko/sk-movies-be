package com.moviesApp.repositories;

import com.moviesApp.entities.MoviePosterEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MoviePosterEmbeddingRepository extends JpaRepository<MoviePosterEmbedding, String> {
}
