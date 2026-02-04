package com.moviesApp.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Config;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Neo4jConfig {

    @Value("${NEO4J_URI}")
    private String uri;

    @Value("${NEO4J_USER}")
    private String user;

    @Value("${NEO4J_PASSWORD}")
    private String password;

    @Bean
    public Driver neo4jDriver() {
        Config.ConfigBuilder builder = Config.builder()
                .withConnectionAcquisitionTimeout(120, TimeUnit.SECONDS)
                .withMaxConnectionLifetime(30, TimeUnit.MINUTES)
                .withMaxConnectionPoolSize(50)
                .withConnectionTimeout(30, TimeUnit.SECONDS);
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password),
                builder.build());
    }

}