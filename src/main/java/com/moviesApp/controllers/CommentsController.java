package com.moviesApp.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.beans.factory.annotation.Autowired;
import com.moviesApp.entities.Comment;
import com.moviesApp.service.CommentsService;

import java.util.List;

@RestController
public class CommentsController {

    @Autowired
    private CommentsService commentsService;

    @GetMapping("/getAllComments")
    public ResponseEntity<List<Comment>> getAllUsers() {
        List<Comment> comments = commentsService.getAllComments();
        return ResponseEntity.ok(comments);
    }

    @GetMapping("/comments/movie/{movieId}")
    public ResponseEntity<List<Comment>> getCommentsByMovieId(@PathVariable String movieId) {
        List<Comment> comments = commentsService.getCommentsByMovieId(movieId);
        return ResponseEntity.ok(comments);
    }

    @GetMapping("/comments/user/{userId}")
    public ResponseEntity<List<Comment>> getCommentsByUserId(@PathVariable String userId) {
        List<Comment> comments = commentsService.getCommentsByUserId(userId);
        return ResponseEntity.ok(comments);
    }
}
