package com.moviesApp.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.moviesApp.common.CommentWithMovieDto;
import com.moviesApp.entities.Comment;
import com.moviesApp.entities.Movie;
import com.moviesApp.repositories.CommentRepo;

@Service
public class CommentService {

    private final CommentRepo commentRepo;

    @Autowired
    public CommentService(CommentRepo commentRepo) {
        this.commentRepo = commentRepo;
    }

    public List<Comment> getCommentsByUserId(String userId) {

        // List<Comment> comments = CommentRepo.findByUserId(userId);

        // // 3. DTO with movie title
        // return comments.stream()
        //         .map(comment -> {
        //             Movie movie = comment.getMovie();
        //             return new CommentWithMovieDto(comment, movie);
        //         })
        //         .toList();

        return commentRepo.findByUserId(userId);
    }
}