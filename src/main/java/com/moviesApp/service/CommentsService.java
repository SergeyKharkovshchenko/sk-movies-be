package com.moviesApp.service;

import java.util.List;
import java.util.Map;

import org.neo4j.driver.types.Node;

import org.springframework.stereotype.Service;

import com.moviesApp.entities.Comment;
import com.moviesApp.entities.Movie;

@Service
public class CommentsService {

    private final Neo4jService neo4j;

    public CommentsService(Neo4jService neo4j) {
        this.neo4j = neo4j;
    }

    public List<Comment> getCommentsByMovieId(String movieId) {
        String query = "MATCH (m:Movie {movieId:'" + movieId
                + "'})-[:MOVIES]->(rv:Review)<-[r1]-(ur:UserReview) RETURN rv, m, ur  LIMIT 20";

        return neo4j.queryList(
                query,
                Map.of(),
                r -> {
                    Node commentNode = r.get("rv").asNode();
                    Node movieNode = r.get("m").asNode();
                    Node userReviewNode = r.get("ur").asNode();
                    Comment comment = new Comment();
                    comment.setId(commentNode.get("id").asString());
                    comment.setUserId(userReviewNode.get(":START_ID(User)").asString());
                    // comment.setMovieId(commentNode.get("Movie_id").asString());
                    comment.setMovieTitle(movieNode.get("movieTitle").asString());
                    comment.setIsSpoiler(commentNode.get("isSpoiler").asBoolean());
                    comment.setRating(commentNode.get("rating").asInt());
                    comment.setReviewDate(commentNode.get("reviewDate").asString());
                    comment.setReviewSummary(commentNode.get("reviewSummary").asString());
                    comment.setReviewText(commentNode.get("reviewText").asString());
                    return comment;
                });
    }

    public List<Comment> getCommentsByUserId(String userId) {

        String query = "MATCH (m:Movie)<-[REVIEWED_FROM_CSV]-(rv:Review)-[`USERS_NEO4J.CSV`]->(u:User ) " +
                "WHERE u.userId = '" + userId + "' " +
                "RETURN m, rv " +
                "LIMIT 20";

        return neo4j.queryList(
                query,
                Map.of(),
                r -> {
                    Node commentNode = r.get("rv").asNode();
                    Node movieNode = r.get("m").asNode();
                    Comment comment = new Comment();
                    comment.setId(commentNode.get("id").asString());
                    comment.setUserId(commentNode.get(":START_ID(User)").asString());
                    comment.setMovieId(commentNode.get("Movie_id").asString());
                    comment.setMovieTitle(movieNode.get("movieTitle").asString());
                    comment.setIsSpoiler(commentNode.get("isSpoiler").asBoolean());
                    comment.setRating(commentNode.get("rating").asInt());
                    comment.setReviewDate(commentNode.get("reviewDate").asString());
                    comment.setReviewSummary(commentNode.get("reviewSummary").asString());
                    comment.setReviewText(commentNode.get("reviewText").asString());
                    return comment;
                });
    }

    public List<Comment> getAllComments() {
        String query = "MATCH (m:Movie {movieId: 'tt0110912'})<-[:REVIEWED_FROM_CSV]-(u:UserReview) RETURN u";

        return neo4j.queryList(
                query,
                Map.of(),
                r -> {
                    Node commentNode = r.get("c").asNode();
                    Comment comment = new Comment();
                    comment.setId(commentNode.get("id").asString());
                    comment.setUserId(commentNode.get(":START_ID(User)").asString());
                    comment.setMovieId(commentNode.get("Movie_id").asString());
                    comment.setIsSpoiler(commentNode.get("isSpoiler").asBoolean());
                    comment.setRating(commentNode.get("rating").asInt());
                    comment.setReviewDate(commentNode.get("reviewDate").asString());
                    comment.setReviewSummary(commentNode.get("reviewSummary").asString());
                    comment.setReviewText(commentNode.get("reviewText").asString());
                    return comment;
                });
    }

}
