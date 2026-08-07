package com.moviesApp.service;

import com.moviesApp.entities.Relation;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RelationService {

    private final Driver driver;

    public RelationService(@Qualifier("neo4jDriver2") Driver driver) {
        this.driver = driver;
    }

    @Cacheable(value = "parentChildRelationsCache", unless = "#result == null || #result.isEmpty()")
    public List<Relation> getParentChildRelations() {
        String cypher = "MATCH (p:Node)-[r:RELATED_TO]->(c:Node) RETURN p.name AS parent, c.name AS child";
        try (Session session = driver.session()) {
            Result result = session.run(cypher);
            return result.list(record -> new Relation(
                    record.get("parent").asString(),
                    record.get("child").asString()
            ));
        }
    }

}
