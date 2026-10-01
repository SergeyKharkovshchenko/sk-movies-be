package com.moviesApp.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

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

    // Aura/Bolt connections sitting idle in the pool get silently dropped by an intermediate
    // network hop (load balancer / NAT / corporate proxy) well before the driver's own
    // maxConnectionLifetime. Without a liveness check, the driver hands out that dead
    // connection on the next request and it fails hard with SessionExpiredException /
    // "Connection reset" instead of transparently reconnecting -- observed twice in one
    // session after ~1-2h idle. withConnectionLivenessCheckTimeout makes the driver ping any
    // pooled connection that's been idle longer than this before reusing it, replacing it
    // silently if it's dead.
    private static final Config DRIVER_CONFIG = Config.builder()
            .withConnectionLivenessCheckTimeout(2, TimeUnit.MINUTES)
            .withMaxConnectionLifetime(50, TimeUnit.MINUTES)
            .build();

    @Primary
    @Bean
    public Driver neo4jDriver() {
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password), DRIVER_CONFIG);
    }

    @Bean
    public Driver neo4jDriver2() {
        return GraphDatabase.driver(uri2, AuthTokens.basic(user2, password2), DRIVER_CONFIG);
    }

}