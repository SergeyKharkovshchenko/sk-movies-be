package com.moviesApp.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MovieServiceTest {

    @SuppressWarnings("unchecked")
    private int limitSent(java.util.function.Consumer<MovieService> call) {
        Neo4jService neo4j = mock(Neo4jService.class);
        when(neo4j.queryList(anyString(), any(Map.class), any(Function.class))).thenReturn(List.of());
        call.accept(new MovieService(neo4j));

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(neo4j).queryList(query.capture(), params.capture(), any(Function.class));
        assertTrue(query.getValue().endsWith("LIMIT $limit"), "limit must be a query parameter");
        return ((Number) params.getValue().get("limit")).intValue();
    }

    @Test
    void defaultsToTwenty() {
        assertEquals(20, limitSent(MovieService::getAllMovies));
    }

    @Test
    void usesTheRequestedLimit() {
        assertEquals(50, limitSent(s -> s.getAllMovies(50)));
    }

    @Test
    void clampsToTheAllowedRange() {
        assertEquals(MovieService.MAX_LIMIT, limitSent(s -> s.getAllMovies(100_000)));
        assertEquals(1, limitSent(s -> s.getAllMovies(0)));
        assertEquals(1, limitSent(s -> s.getAllMovies(-5)));
    }
}
