package com.moviesApp.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moviesApp.entities.Movie;
import com.moviesApp.service.MovieService;

@RestController
public class MovieController {

    @Autowired
    private MovieService movieService;

    @GetMapping("/getMoviesNamesByActor")
    public ResponseEntity<Iterable<String>> getMoviesNamesByActor() {
        Iterable<String> movies = movieService.getMoviesNamesByActor();
        return ResponseEntity.ok(movies);
    }

    @GetMapping("/getMoviesByActor")
public ResponseEntity<Iterable<Movie>> getMoviesByActor(
        @RequestParam("actor") String actorName
) {
    Iterable<Movie> movies = movieService.getMoviesByActor(actorName);
    return ResponseEntity.ok(movies);
}

}
