package com.moviesApp.entities;

import org.springframework.data.annotation.Id;

public class User {

    @Id
    private String id;
    private String userId;
    private Number numberOfReviews;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setNumberOfReviews(Number numberOfReviews) {
        this.numberOfReviews = numberOfReviews;
    }

    public Number getNumberOfReviews() {
        return numberOfReviews;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

}
