package com.moviesApp.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Neo4jServiceTest {

    private final List<String> started = new ArrayList<>();
    private final List<String> tags = new ArrayList<>();

    private ObservationRegistry registry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                started.add(context.getName());
                context.getLowCardinalityKeyValues().forEach(kv -> tags.add(kv.getKey() + "=" + kv.getValue()));
            }
        });
        return registry;
    }

    private Session sessionReturning(Result result, Driver driver) {
        Session session = mock(Session.class);
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        return session;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listQueryIsRecordedAsASpanAndStillReturnsItsRows() {
        Driver driver = mock(Driver.class);
        Result result = mock(Result.class);
        when(result.list(any(Function.class))).thenReturn(List.of("row"));
        Session session = sessionReturning(result, driver);

        List<String> rows = new Neo4jService(driver, registry()).queryList("MATCH (n) RETURN n", Map.of(), r -> "x");

        assertEquals(List.of("row"), rows);
        assertEquals(List.of("neo4j.query"), started);
        assertTrue(tags.contains("db.system=neo4j"));
        assertTrue(tags.contains("db.operation=list"));
        verify(session).close();
    }

    @Test
    void theCypherTextIsNeverRecordedOnTheSpan() {
        Driver driver = mock(Driver.class);
        Result result = mock(Result.class);
        when(result.hasNext()).thenReturn(true);
        sessionReturning(result, driver);

        assertTrue(new Neo4jService(driver, registry()).exists("MATCH (u {userId:'ur1'}) RETURN u", Map.of()));

        assertTrue(tags.contains("db.operation=exists"));
        assertFalse(tags.stream().anyMatch(t -> t.contains("ur1") || t.contains("MATCH")));
    }

    @Test
    void singleReturnsNullWhenThereIsNoRow() {
        Driver driver = mock(Driver.class);
        Result result = mock(Result.class);
        when(result.hasNext()).thenReturn(false);
        sessionReturning(result, driver);

        assertNull(new Neo4jService(driver, registry()).querySingle("MATCH (n) RETURN n", Map.of(), r -> "x"));
        assertTrue(tags.contains("db.operation=single"));
    }

    @Test
    void aFailingQueryStillClosesTheSessionAndPropagates() {
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class,
                () -> new Neo4jService(driver, registry()).queryList("MATCH (n) RETURN n", Map.of(), r -> "x"));
        verify(session).close();
    }
}
