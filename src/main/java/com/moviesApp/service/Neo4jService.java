package com.moviesApp.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
public class Neo4jService {

    private final Driver driver;
    private final ObservationRegistry observations;

    public Neo4jService(Driver driver, ObservationRegistry observations) {
        this.driver = driver;
        this.observations = observations;
    }

    /**
     * Each query becomes a child span of the current request (shown in the trace waterfall). Only low
     * cardinality tags are set: the Cypher text can carry ids and is deliberately not recorded.
     */
    private <T> T observed(String operation, java.util.function.Supplier<T> call) {
        return Observation.createNotStarted("neo4j.query", observations)
                .lowCardinalityKeyValue("db.system", "neo4j")
                .lowCardinalityKeyValue("db.operation", operation)
                .observe(call);
    }

    public <T> List<T> queryList(String cypher, Map<String, Object> params, Function<Record, T> mapper) {
        return observed("list", () -> {
            try (Session session = driver.session()) {
                Result result = session.run(cypher, params);
                return result.list(mapper::apply);
            }
        });
    }

    public <T> T querySingle(String cypher, Map<String, Object> params, Function<Record, T> mapper) {
        return observed("single", () -> {
            try (Session session = driver.session()) {
                Result result = session.run(cypher, params);
                if (!result.hasNext()) {
                    return null;
                }
                Record record = result.single();
                return mapper.apply(record);
            }
        });
    }

    public boolean exists(String cypher, Map<String, Object> params) {
        return observed("exists", () -> {
            try (Session session = driver.session()) {
                Result result = session.run(cypher, params);
                return result.hasNext();
            }
        });
    }
}
