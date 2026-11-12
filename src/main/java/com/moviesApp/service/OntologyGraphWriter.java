package com.moviesApp.service;

import com.moviesApp.repositories.BikeEmbeddingRepository;
import com.moviesApp.service.ModelReconciler.EdgeInfo;
import com.moviesApp.service.ModelReconciler.EdgeRemoval;
import com.moviesApp.service.ModelReconciler.NodeInfo;
import com.moviesApp.service.ModelReconciler.Plan;
import com.moviesApp.service.ModelReconciler.Snapshot;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.moviesApp.service.OntologyRules.listOf;
import static com.moviesApp.service.OntologyRules.str;

/** Reads and writes the knowledge graph for the ontology pipeline (Neo4j) and keeps its embeddings in step. */
@Service
public class OntologyGraphWriter {

    private static final String SOURCE_TYPE = "knowledge_node";
    private static final String CHUNK_SOURCE_TYPE = "chunk_text";

    private final Driver driver;
    private final BikeEmbeddingRepository repository;

    public OntologyGraphWriter(@Qualifier("neo4jDriver2") Driver driver, BikeEmbeddingRepository repository) {
        this.driver = driver;
        this.repository = repository;
    }

    private static String ident(String s) {
        return s.replace("`", "``");
    }

    private static String safe(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    public Snapshot snapshot(String label) {
        Map<String, Object> p = Map.of("l", label);
        try (Session session = driver.session()) { // Neo4j
            List<NodeInfo> nodes = session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) RETURN n.name AS name, (n:Section OR n:Chunk) AS structural", p
            ).list(r -> new NodeInfo(r.get("name").asString(), r.get("structural").asBoolean(false)));
            List<EdgeInfo> edges = session.run(
                    "MATCH (a:KGNode {sourceLabel: $l})-[r]->(b:KGNode {sourceLabel: $l}) " +
                            "RETURN a.name AS f, type(r) AS t, b.name AS o, coalesce(r.inferred, false) AS inf", p
            ).list(r -> new EdgeInfo(r.get("f").asString(), r.get("t").asString(), r.get("o").asString(),
                    r.get("inf").asBoolean(false)));
            Set<String> nodeProps = new LinkedHashSet<>(session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) UNWIND keys(n) AS k RETURN DISTINCT k", p
            ).list(r -> r.get("k").asString()));
            Set<String> relProps = new LinkedHashSet<>(session.run(
                    "MATCH (:KGNode {sourceLabel: $l})-[r]->(:KGNode {sourceLabel: $l}) UNWIND keys(r) AS k RETURN DISTINCT k", p
            ).list(r -> r.get("k").asString()));
            return new Snapshot(nodes, edges, nodeProps, relProps);
        }
    }

    /** Creates or updates one node per vocabulary term: class label, definition, aliases. */
    public void upsertTerms(String label, List<Map<String, Object>> terms, String origin) {
        try (Session session = driver.session()) { // Neo4j
            for (Map<String, Object> t : terms) {
                String type = str(t, "type");
                // MERGE on identity only, then add labels -- a label in the MERGE pattern would create a
                // second node whenever the existing one carries a different label.
                session.run("MERGE (n:KGNode {name: $name, sourceLabel: $l}) " +
                                "ON CREATE SET n.nodeType = 'entity', n.origin = $origin " +
                                "SET n:`" + ident(safe(label)) + "`" + (type.isEmpty() ? "" : ":`" + ident(safe(type)) + "`") +
                                ", n.definition = $def, n.aliases = $aliases, " +
                                "n.description = CASE WHEN $def <> '' AND n.description IS NULL THEN $def ELSE n.description END",
                        Map.of("name", str(t, "term"), "l", label, "origin", origin,
                                "def", str(t, "definition"), "aliases", OntologyRules.strings(t.get("aliases"))));
            }
        }
    }

    /** Stores one approved triple with its provenance; never creates nodes (the vocabulary already did). */
    public void storeTriple(String label, String subject, String predicate, String object,
                            String evidence, String section, String chunk) {
        try (Session session = driver.session()) { // Neo4j
            session.run("MATCH (a:KGNode {name: $s, sourceLabel: $l}) MATCH (b:KGNode {name: $o, sourceLabel: $l}) " +
                            "MERGE (a)-[r:`" + ident(safe(predicate)) + "` {predicate: $p}]->(b) " +
                            "SET r.evidence = $e, r.section = $sec, r.chunk = $c, r.origin = 'llm', r.status = 'approved'",
                    Map.of("s", subject, "o", object, "l", label, "p", predicate,
                            "e", evidence == null ? "" : evidence, "sec", section, "c", chunk));
        }
    }

    /**
     * Applies a reconciliation plan. Derived (inferred) edges are always dropped here, with their
     * sentences, because the caller rebuilds them from the new taxonomy.
     */
    public Map<String, Object> apply(String label, Plan plan) {
        Map<String, Object> p = Map.of("l", label);
        Map<String, Object> counts = new LinkedHashMap<>();
        try (Session session = driver.session()) { // Neo4j
            List<String> inferredSentences = session.run(
                    "MATCH (a:KGNode {sourceLabel: $l})-[r]->(c:KGNode {sourceLabel: $l}) WHERE r.inferred = true " +
                            "RETURN a.name + ' [' + c.name + ']' AS name", p).list(r -> r.get("name").asString());
            session.run("MATCH (:KGNode {sourceLabel: $l})-[r]->(:KGNode {sourceLabel: $l}) WHERE r.inferred = true DELETE r", p);
            if (!inferredSentences.isEmpty()) {
                repository.deleteBySourceTypeAndLabelsAndNameIn(CHUNK_SOURCE_TYPE, label, inferredSentences);
            }

            for (EdgeRemoval removal : plan.deleteEdges()) {
                EdgeInfo e = removal.edge();
                session.run("MATCH (a:KGNode {name: $f, sourceLabel: $l})-[r:`" + ident(safe(e.type())) + "`]->" +
                        "(b:KGNode {name: $t, sourceLabel: $l}) DELETE r", Map.of("f", e.from(), "t", e.to(), "l", label));
            }
            counts.put("edgesRemoved", plan.deleteEdges().size());

            for (String name : plan.deleteNodes()) {
                session.run("MATCH (n:KGNode {name: $n, sourceLabel: $l}) DETACH DELETE n", Map.of("n", name, "l", label));
            }
            if (!plan.deleteNodes().isEmpty()) {
                repository.deleteBySourceTypeAndLabelsAndNameIn(SOURCE_TYPE, label, plan.deleteNodes());
                repository.deleteBySourceTypeAndLabelsAndNameIn(CHUNK_SOURCE_TYPE, label, plan.deleteNodes());
            }
            counts.put("nodesRemoved", plan.deleteNodes().size());

            for (String key : plan.removeNodeProps()) {
                session.run("MATCH (n:KGNode {sourceLabel: $l}) REMOVE n.`" + ident(key) + "`", p);
            }
            for (String key : plan.removeRelProps()) {
                session.run("MATCH (:KGNode {sourceLabel: $l})-[r]->(:KGNode {sourceLabel: $l}) REMOVE r.`" + ident(key) + "`", p);
            }
            counts.put("propertiesRemoved", plan.removeNodeProps().size() + plan.removeRelProps().size());
        }
        upsertTerms(label, plan.upsertTerms(), "vocabulary");
        counts.put("termsWritten", plan.upsertTerms().size());

        try (Session session = driver.session()) { // Neo4j
            for (Map<String, String> pair : plan.addIsA()) {
                session.run("MATCH (c:KGNode {name: $c, sourceLabel: $l}) MATCH (p:KGNode {name: $p, sourceLabel: $l}) " +
                        "MERGE (c)-[:IS_A]->(p)", Map.of("c", pair.get("child"), "p", pair.get("parent"), "l", label));
            }
        }
        counts.put("taxonomyEdgesAdded", plan.addIsA().size());
        return counts;
    }

    /** Stores the ontology itself next to the graph (own labels, so it never shows up as knowledge). */
    public void publishOntology(String label, Map<String, Object> ontology) {
        try (Session session = driver.session()) { // Neo4j
            session.run("MATCH (n) WHERE (n:OntClass OR n:OntRelation) AND n.sourceLabel = $l DETACH DELETE n", Map.of("l", label));
            for (Map<String, Object> c : listOf(ontology, "classes")) {
                session.run("CREATE (:OntClass {name: $n, parent: $p, description: $d, sourceLabel: $l})",
                        Map.of("n", str(c, "name"), "p", str(c, "parent"), "d", str(c, "description"), "l", label));
            }
            for (Map<String, Object> r : listOf(ontology, "relations")) {
                session.run("CREATE (:OntRelation {name: $n, domain: $dom, range: $rng, description: $d, sourceLabel: $l})",
                        Map.of("n", str(r, "name"), "dom", str(r, "domain"), "rng", str(r, "range"),
                                "d", str(r, "description"), "l", label));
            }
        }
    }

    /** Names of all non-structural nodes (for the review UI to offer as known terms). */
    public List<String> nodeNames(String label) {
        List<String> out = new ArrayList<>();
        try (Session session = driver.session()) { // Neo4j
            session.run("MATCH (n:KGNode {sourceLabel: $l}) WHERE NOT n:Section AND NOT n:Chunk RETURN n.name AS name",
                    Map.of("l", label)).forEachRemaining(r -> out.add(r.get("name").asString()));
        }
        return out;
    }
}
