package com.moviesApp.service;

import com.moviesApp.entities.Movie;
import org.neo4j.driver.types.Node;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MovieService {

    private final Neo4jService neo4j;

    public MovieService(Neo4jService neo4j) {
        this.neo4j = neo4j;
    }

    public List<Movie> getAllMovies() {

        String query = "MATCH (m:Movie)<-[:REVIEWED_FROM_CSV]-(u:UserReview) " +
                "WHERE u.rating IS NOT NULL " +
                "WITH DISTINCT m " +
                "MATCH (m:Movie)-[MOVIES]->(mg:MovieGenre)-[GENRES]->(g:Genre) " +
                "WITH m, collect(g) AS genres " +
                "MATCH (m:Movie)<-[:MOVIES]-(r:Review)" +
                "WITH avg(r.rating) AS averageRating, m, genres " +
                "RETURN m, genres[0] AS g0, genres[1] AS g1, genres[2] AS g2, averageRating " +
                "LIMIT 20";

        return neo4j.queryList(
                query,
                Map.of(),
                r -> {
                    Node movieNode = r.get("m").asNode();
                    Node genre0 = r.get("g0").isNull() ? null : r.get("g0").asNode();
                    Node genre1 = r.get("g1").isNull() ? null : r.get("g1").asNode();
                    Node genre2 = r.get("g2").isNull() ? null : r.get("g2").asNode();
                    Number avRating = r.get("averageRating").asNumber();
                    Movie movie = new Movie();
                    movie.setId(movieNode.get("id").asString());
                    movie.setDuration(movieNode.get("duration").asString());
                    movie.setGenre0(genre0.get("genreName").asString());
                    if (genre1 != null) {
                        movie.setGenre1(genre1.get("genreName").asString());
                    }
                    if (genre2 != null) {
                        movie.setGenre1(genre2.get("genreName").asString());
                    }
                    movie.setMovieId(movieNode.get("movieId").asString());
                    movie.setMovieTitle(movieNode.get("movieTitle").asString());
                    movie.setPlotSummary(movieNode.get("plotSummary").asString());
                    movie.setAverageRating(avRating);
                    return movie;
                });
    }

}
