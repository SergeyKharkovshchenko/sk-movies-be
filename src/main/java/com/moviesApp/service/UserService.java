package com.moviesApp.service;

import java.util.List;
import java.util.Map;

import org.neo4j.driver.types.Node;

import org.springframework.stereotype.Service;

import com.moviesApp.entities.User;

@Service
public class UserService {

    private final Neo4jService neo4j;

    public UserService(Neo4jService neo4j) {
        this.neo4j = neo4j;
    }

    public List<User> getAllUsers() {
        String query = "MATCH (u:User)<-[r:`USERS_NEO4J.CSV`]-(:Review) " +
                "WITH u, count(r) AS numReviews " +
                "ORDER BY numReviews DESC " +
                "RETURN u, numReviews " +
                "LIMIT 20";

        return neo4j.queryList(
                query,
                Map.of(),
                r -> {
                    Node userNode = r.get("u").asNode();
                    User user = new User();
                    user.setId(userNode.get("id").asString());
                    user.setUserId(userNode.get("userId").asString());
                    user.setNumberOfReviews(r.get("numReviews").asNumber());
                    return user;
                });
    }

}
