package com.moviesApp.controllers;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.moviesApp.common.CommentWithUserDto;
import com.moviesApp.entities.Comment;
import com.moviesApp.service.MovieService;

@RestController
public class CommentsController {

    @Autowired
    private MovieService movieService;

    @PostMapping("/{movieId}/comments")
    public Comment createComment(@PathVariable("movieId") Long id, @RequestBody Comment comment) {
        return movieService.createComment(id, comment);
    }

    @GetMapping("/{movieId}/comments")
    public List<CommentWithUserDto> createComment2(@PathVariable("movieId") Long id) {
        return movieService.getCommentsByMovieId(id);
    }

}
