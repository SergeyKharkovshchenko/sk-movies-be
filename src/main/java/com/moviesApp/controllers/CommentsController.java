package com.moviesApp.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
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

    @CrossOrigin(origins = { "http://localhost:5173", "https://sk-movies-fe.vercel.app" })
    @GetMapping("/getAllComments")
    public ResponseEntity<Iterable<Comment>> getAllUsers() {
        Iterable<Comment> users = commentsService.getAllComments();
        return ResponseEntity.ok(users);
    }

    @CrossOrigin(origins = { "http://localhost:5173", "https://sk-movies-fe.vercel.app" })
    @GetMapping("/comments/movie/{movieId}")
    public ResponseEntity<Iterable<Comment>> getCommentsByMovieId(@PathVariable String movieId) {
        Iterable<Comment> comments = commentsService.getCommentsByMovieId(movieId);
        return ResponseEntity.ok(comments);
    }

    @CrossOrigin(origins = { "http://localhost:5173", "https://sk-movies-fe.vercel.app" })
    @GetMapping("/comments/user/{userId}")
    public ResponseEntity<Iterable<Comment>> getCommentsByUserId(@PathVariable String userId) {
        Iterable<Comment> comments = commentsService.getCommentsByUserId(userId);
        return ResponseEntity.ok(comments);
    }

}
