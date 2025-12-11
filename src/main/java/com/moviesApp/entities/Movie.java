package com.moviesApp.entities;

import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "movies")
public class Movie {

    private Long id;
    private String title;
    private String tagline;
    private Integer released;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTagline() {
        return tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }

    public Integer getReleaseDate() {
        return released;
    }

    public void setReleaseDate(Integer released) {
        this.released = released;
    }

}
