package com.moviesApp.controllers;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.moviesApp.common.CommentWithMovieDto;
import com.moviesApp.entities.Movie;
import com.moviesApp.entities.User;
import com.moviesApp.service.CommentService;
import com.moviesApp.service.UserService;

@RestController
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private CommentService commentService;

    @PostMapping("/createUser")
    public ResponseEntity<User> createNewUser(@RequestBody User user) {
        User newUser = userService.createNewUser(user);
        return ResponseEntity.ok(newUser);
    }

    @GetMapping("/getAllUsers")
    public ResponseEntity<Iterable<User>> getAllUsers() {
        Iterable<User> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/comments/user/{userId}")
    public ResponseEntity<List<CommentWithMovieDto>> getCommentsByUserId(
            @PathVariable String userId) {
        List<CommentWithMovieDto> comments = commentService.getCommentsByUserId(userId).stream()
                .map(comment -> {
                    Movie movie = comment.getMovie();
                    return new CommentWithMovieDto(comment, movie);
                })
                .toList();

        return ResponseEntity.ok(comments);
    }
}
