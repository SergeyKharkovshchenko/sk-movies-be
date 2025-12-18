package com.moviesApp.repositories;

import org.springframework.stereotype.Repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.moviesApp.entities.Comment;

@Repository
public interface CommentRepo extends MongoRepository<Comment, String> {
    // parses to internal command db.comments.find({ "movieId": 123 })
    List<Comment> findByMovieId(Long movieId);
}
