package com.moviesApp.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.moviesApp.entities.User;
import com.moviesApp.entities.Movie;
import com.moviesApp.service.UserService;
import com.moviesApp.service.MovieService;

@RestController
public class MovieController {

    @Autowired
    private MovieService movieService;

    @GetMapping("/getMoviesByActor")
    public ResponseEntity<Iterable<String>> getMoviesByActor() {
        Iterable<String> movies = movieService.getMoviesByActor();
        return ResponseEntity.ok(movies);
    }

    @GetMapping("/getMoviesByActor2")
    public ResponseEntity<Iterable<Movie>> getMoviesByActor2() {
        Iterable<Movie> movies = movieService.getMoviesByActor2();
        return ResponseEntity.ok(movies);
    }

}
