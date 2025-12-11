
package com.moviesApp.service;

import org.springframework.stereotype.Service;

import com.moviesApp.entities.Movie;

import java.util.List;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

@Service
public class MovieService {

    public Iterable<String> getMoviesByActor() {

        final String dbUri = "neo4j+s://270cf0e6.databases.neo4j.io";
        final String dbUser = "neo4j";
        final String dbPassword = "Aa4Zezsc_85f4Xu8i4pCu4p9S0Izr6ju2Pn9oJIEuXM";
        String query = "MATCH (p:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(m:Movie) RETURN m.title LIMIT 5";

        try (Driver driver = GraphDatabase.driver(dbUri, AuthTokens.basic(dbUser, dbPassword));
                Session session = driver.session()) {

            Result result = session.run(query);
            return result.list(r -> r.get("m.title").asString());
        } catch (Exception e) {
            System.err.println("Error executing query: " + e.getMessage());
        }
        return null;
    }

    public List<Movie> getMoviesByActor2() {

        final String dbUri = "neo4j+s://270cf0e6.databases.neo4j.io";
        final String dbUser = "neo4j";
        final String dbPassword = "Aa4Zezsc_85f4Xu8i4pCu4p9S0Izr6ju2Pn9oJIEuXM";
        String query = "MATCH (p:Person {name: 'Tom Hanks'})-[:ACTED_IN]->(m:Movie) RETURN m LIMIT 5";

        try (Driver driver = GraphDatabase.driver(dbUri, AuthTokens.basic(dbUser, dbPassword));
                Session session = driver.session()) {

            Result result = session.run(query);
          
            // while (result.hasNext()) {
            // Record record = result.next();
            // Node movie = record.get("m").asNode();
            // movie.asMap().forEach((key, value) -> System.out.println(" " + key + " = " +
            // value));
            // System.out.println("-----");
            // }

            return result.list(r -> {
                Node movieNode = r.get("m").asNode();
                Movie movie = new Movie();
                movie.setId(movieNode.id());
                movie.setTitle(movieNode.get("title").asString());
                movie.setTagline(movieNode.get("tagline").asString());
                movie.setReleaseDate(movieNode.get("released").asInt());
                System.out.println(movie);
                return movie;
            });

  
        } catch (Exception e) {
            System.err.println("Error executing query: " + e.getMessage());
        }
        return null;
    }

}
