package com.moviesApp.entities;

import org.springframework.data.annotation.Id;

public class Comment {

    @Id
    public String id;
    private String userId;
    private String movieId;
    private String movieTitle;
    private boolean isSpoiler;
    private Number rating;
    private String reviewDate;
    private String reviewSummary;
    private String reviewText;

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMovieId() {
        return movieId;
    }

    public void setMovieId(String movieId) {
        this.movieId = movieId;
    }

    public String getMovieTitle() {
        return movieTitle;
    }

    public void setMovieTitle(String movieTitle) {
        this.movieTitle = movieTitle;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public boolean isSpoiler() {
        return isSpoiler;
    }

    public void setIsSpoiler(boolean isSpoiler) {
        this.isSpoiler = isSpoiler;
    }

    public Number getRating() {
        return rating;
    }

    public void setRating(Number rating) {
        this.rating = rating;
    }

    public String getReviewDate() {
        return reviewDate;
    }

    public void setReviewDate(String reviewDate) {
        this.reviewDate = reviewDate;
    }

    public String getReviewSummary() {
        return reviewSummary;
    }

    public void setReviewSummary(String reviewSummary) {
        this.reviewSummary = reviewSummary;
    }

    public String getReviewText() {
        return reviewText;
    }

    public void setReviewText(String reviewText) {
        this.reviewText = reviewText;
    }

}
