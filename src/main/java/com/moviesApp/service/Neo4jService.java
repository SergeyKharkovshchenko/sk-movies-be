package com.moviesApp.service;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
public class Neo4jService {

    private final Driver driver;

    public Neo4jService(Driver driver) {
        this.driver = driver;
    }

    public <T> List<T> queryList(String cypher, Map<String, Object> params, Function<Record, T> mapper) {
        try (Session session = driver.session()) {
            Result result = session.run(cypher, params);
            return result.list(mapper::apply);
        }
    }

    public <T> T querySingle(String cypher, Map<String, Object> params, Function<Record, T> mapper) {
        try (Session session = driver.session()) {
            Result result = session.run(cypher, params);
            if (!result.hasNext()) {
                return null;
            }
            Record record = result.single();
            return mapper.apply(record);
        }
    }

    public boolean exists(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            Result result = session.run(cypher, params);
            return result.hasNext();
        }
    }
}

