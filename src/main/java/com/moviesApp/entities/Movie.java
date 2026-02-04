package com.moviesApp.entities;

public class Movie {

    private String id;
    private String duration;
    private String genre0;
    private String genre1;
    private String genre2;
    private String movieId;
    private String movieTitle;
    private String plotSummary;
    private Number averageRating;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Number getAverageRating() {
        return averageRating;
    }

    public void setAverageRating(Number averageRating) {
        this.averageRating = averageRating;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getGenre0() {
        return genre0;
    }

    public void setGenre0(String genre0) {
        this.genre0 = genre0;
    }

    public String getGenre1() {
        return genre1;
    }

    public void setGenre1(String genre1) {
        this.genre1 = genre1;
    }

    public String getGenre2() {
        return genre2;
    }

    public void setGenre2(String genre2) {
        this.genre2 = genre2;
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

    public String getPlotSummary() {
        return plotSummary;
    }

    public void setPlotSummary(String plotSummary) {
        this.plotSummary = plotSummary;
    }

}
