package com.moviesApp.repositories;

import org.springframework.stereotype.Repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.moviesApp.entities.Comment;

@Repository
public interface CommentRepo extends MongoRepository<Comment, String> {
    List<Comment> findByUserId(String userId);

    // parses to internal command db.comments.find({ "movieId": 123 })
    List<Comment> findByMovieId(Long movieId);
}
