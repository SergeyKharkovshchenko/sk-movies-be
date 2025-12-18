package com.moviesApp.common;

import java.util.Date;
import com.moviesApp.entities.Comment;
import com.moviesApp.entities.Movie;

public class CommentWithUserDto {
    private String commentId;
    private String content;
    private Date createdAt;
    private String movieId;
    private Movie movie;
    private String userId;
    private String username;

    public CommentWithUserDto(Comment comment, String username) {
        this.commentId = comment.getCommentId();
        this.content = comment.getContent();
        this.createdAt = comment.getCreatedAt();
        this.movieId = comment.getMovie().getId().toString();
        this.movie = comment.getMovie();
        this.userId = comment.getUserId();
        this.username = username;
    }

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public String getMovieId() {
        return movieId;
    }

    public void setMovieId(String movieId) {
        this.movieId = movieId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Movie getMovie() {
        return movie;
    }

    public void setMovie(Movie movie) {
        this.movie = movie;
    }
}
