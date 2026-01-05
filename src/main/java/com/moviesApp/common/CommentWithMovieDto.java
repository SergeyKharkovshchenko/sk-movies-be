package com.moviesApp.common;

import com.moviesApp.entities.Comment;
import com.moviesApp.entities.Movie;

public class CommentWithMovieDto {
    private Comment comment;
    private Movie movie;

    public CommentWithMovieDto(Comment comment, Movie movie) {
        this.comment = comment;
        this.movie = movie;
    }

    public Movie getMovie() {
        return movie;
    }

    public void setMovie(Movie movie) {
        this.movie = movie;
    }

    public Comment getComment() {
        return comment;
    }

    public void setComment(Comment comment) {
        this.comment = comment;
    }
}
