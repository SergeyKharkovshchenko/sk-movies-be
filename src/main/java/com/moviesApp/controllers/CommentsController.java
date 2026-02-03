package com.moviesApp.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.beans.factory.annotation.Autowired;
import com.moviesApp.entities.Comment;
import com.moviesApp.service.CommentsService;

@RestController
public class CommentsController {

    @Autowired
    private CommentsService commentsService;

    @GetMapping("/getAllComments")
    public ResponseEntity<Iterable<Comment>> getAllUsers() {
        Iterable<Comment> users = commentsService.getAllComments();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/comments/movie/{movieId}")
    public ResponseEntity<Iterable<Comment>> getCommentsByMovieId(@PathVariable String movieId) {
        Iterable<Comment> comments = commentsService.getCommentsByMovieId(movieId);
        return ResponseEntity.ok(comments);
    }

    @GetMapping("/comments/user/{userId}")
    public ResponseEntity<Iterable<Comment>> getCommentsByUserId(@PathVariable String userId) {
        Iterable<Comment> comments = commentsService.getCommentsByUserId(userId);
        return ResponseEntity.ok(comments);
    }

}
