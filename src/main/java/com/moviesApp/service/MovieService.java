package com.moviesApp.service;

import com.moviesApp.common.CommentWithUserDto;
import com.moviesApp.entities.Comment;
import com.moviesApp.entities.Movie;
import com.moviesApp.repositories.CommentRepo;
import org.neo4j.driver.types.Node;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class MovieService {

    private final CommentRepo commentRepo;
    private final UserService userService;
    private final Neo4jService neo4j;

    public MovieService(CommentRepo commentRepo, UserService userService, Neo4jService neo4j) {
        this.commentRepo = commentRepo;
        this.userService = userService;
        this.neo4j = neo4j;
    }

    public Iterable<String> getMoviesNamesByActor() {
        String query = """
                    MATCH (p:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(m:Movie)
                    RETURN m.title
                    LIMIT 5
                """;

        return neo4j.queryList(
                query,
                Map.of(),
                r -> r.get("m.title").asString());
    }

    public List<Movie> getMoviesByActor(String actor) {
        String actorName = actor.replace('_',' ');      
        String query = """
                    MATCH (p:Person {name: $actorName})-[:ACTED_IN]->(m:Movie)
                    RETURN m
                    LIMIT 5
                """;

        return neo4j.queryList(
                query,
                Map.of(),
                r -> {
                    Node movieNode = r.get("m").asNode();
                    Movie movie = new Movie();
                    movie.setId(movieNode.id());
                    movie.setTitle(movieNode.get("title").asString());
                    movie.setTagline(movieNode.get("tagline").asString(null));
                    movie.setReleaseDate(movieNode.get("released").asInt());
                    return movie;
                });
    }

    public Movie findMovieById(Long movieId) {
        String query = """
                    MATCH (m:Movie)
                    WHERE id(m) = $movieId
                    RETURN m
                """;

        return neo4j.querySingle(
                query,
                Map.of("movieId", movieId),
                r -> {
                    Node movieNode = r.get("m").asNode();
                    Movie movie = new Movie();
                    movie.setId(movieNode.id());
                    movie.setTitle(movieNode.get("title").asString());
                    movie.setTagline(movieNode.get("tagline").asString(null));
                    movie.setReleaseDate(movieNode.get("released").asInt());
                    return movie;
                });
    }

    public List<CommentWithUserDto> getCommentsByMovieId(Long movieId) {
        // 1. check that movie exists
        String checkQuery = """
                    MATCH (m:Movie)
                    WHERE id(m) = $movieId
                    RETURN m
                    LIMIT 1
                """;

        boolean exists = neo4j.exists(checkQuery, Map.of("movieId", movieId));
        if (!exists) {
            return List.of();
        }

        // 2. Comments from Mongo
        List<Comment> comments = commentRepo.findByMovieId(movieId);

        // 3. DTO with username
        return comments.stream()
                .map(comment -> {
                    String username = userService.findUsernameById(comment.getUserId());
                    return new CommentWithUserDto(comment, username);
                })
                .toList();
    }

    public Comment createComment(Long movieId, Comment comment) {
        Movie existingMovie = findMovieById(movieId);
        if (existingMovie == null) {
            throw new IllegalArgumentException("Movie with id " + movieId + " not found");
        }

        Comment newComment = new Comment();
        newComment.setContent(comment.getContent());
        newComment.setCreatedAt(new Date());
        newComment.setMovie(existingMovie);
        newComment.setUserId(comment.getUserId());

        return commentRepo.save(newComment);
    }
}
