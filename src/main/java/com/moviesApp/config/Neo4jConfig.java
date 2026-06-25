package com.moviesApp.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class Neo4jConfig {

    @Value("${NEO4J_URI}")
    private String uri;

    @Value("${NEO4J_USER}")
    private String user;

    @Value("${NEO4J_PASSWORD}")
    private String password;

    @Value("${NEO4J_URI_2}")
    private String uri2;

    @Value("${NEO4J_USER_2}")
    private String user2;

    @Value("${NEO4J_PASSWORD_2}")
    private String password2;

    @Primary
    @Bean
    public Driver neo4jDriver() {
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    @Bean
    public Driver neo4jDriver2() {
        return GraphDatabase.driver(uri2, AuthTokens.basic(user2, password2));
    }

}