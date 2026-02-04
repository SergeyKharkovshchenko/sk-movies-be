package com.moviesApp.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moviesApp.entities.Movie;
import com.moviesApp.service.MovieService;

@RestController
public class MovieController {

    @Autowired
    private MovieService movieService;

    @GetMapping("/getAllMovies")
    public ResponseEntity<Iterable<Movie>> getAllMovies() {
        Iterable<Movie> movies = movieService.getAllMovies();
        return ResponseEntity.ok(movies);
    }

}
