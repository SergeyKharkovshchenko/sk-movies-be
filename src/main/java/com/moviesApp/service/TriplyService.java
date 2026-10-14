package com.moviesApp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mirrors each knowledge base (one Neo4j `sourceLabel`) into TriplyDB as one named graph in a
 * single dataset, via the Graph Store protocol: after a build the whole graph is re-exported and
 * PUT (replace), and on delete the named graph is removed. Neo4j stays the source of truth --
 * every push is a full snapshot, so a missed or failed sync is fixed by the next one.
 *
 * Calls run on a single-thread executor: off the request/SSE thread (TriplyDB latency or outages
 * never slow down or fail a build), and strictly in submission order, so a quick build-then-delete
 * can't race into a resurrected graph. Disabled entirely when TRIPLY_TOKEN is unset.
 */
@Service
public class TriplyService {

    private static final Logger log = LoggerFactory.getLogger(TriplyService.class);

    private static final String API_URL = "https://api.triplydb.com";
    private static final String APP_URL = "https://triplydb.com";
    private static final String RDF_TYPE   = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String XSD        = "http://www.w3.org/2001/XMLSchema#";

    // Identity/bookkeeping props already expressed as the node IRI + rdfs:label, or not data at all.
    private static final Set<String> SKIPPED_PROPS = Set.of("name", "sourceLabel", "embedding");
    // Graph Store PUT is a single request; TriplyDB recommends the jobs API above ~5 MB.
    private static final int LARGE_UPLOAD_BYTES = 5 * 1024 * 1024;

    @Value("${triply.token:}")
    private String token;

    @Value("${triply.account:Kharkovchenko}")
    private String account;

    @Value("${triply.dataset:sk-knowledge}")
    private String dataset;

    @Value("${triply.access-level:private}")
    private String accessLevel;

    private final Driver          driver;
    private final ObjectMapper    objectMapper;
    private final HttpClient      httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ExecutorService executor   = Executors.newSingleThreadExecutor();
    private volatile boolean      datasetReady;

    public TriplyService(@Qualifier("neo4jDriver2") Driver driver, ObjectMapper objectMapper) {
        this.driver       = driver;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return token != null && !token.isBlank();
    }

    /** Fire-and-forget: export the label's current Neo4j subgraph and replace its TriplyDB graph. */
    public void syncGraphAsync(String label) {
        if (!isEnabled()) return;
        executor.submit(() -> {
            try {
                syncGraph(label);
            } catch (Exception e) {
                log.error("[triply] sync of '{}' failed: {}", label, e.getMessage(), e);
            }
        });
    }

    /** Fire-and-forget: remove the label's named graph from TriplyDB (no-op if it isn't there). */
    public void deleteGraphAsync(String label) {
        if (!isEnabled()) return;
        executor.submit(() -> {
            try {
                deleteGraph(label);
            } catch (Exception e) {
                log.error("[triply] delete of '{}' failed: {}", label, e.getMessage(), e);
            }
        });
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    private void syncGraph(String label) throws Exception {
        String nTriples = exportNTriples(label);
        if (nTriples.isEmpty()) {
            log.info("[triply] '{}' has no nodes in Neo4j -- nothing to push", label);
            return;
        }
        ensureDataset();

        byte[] body = nTriples.getBytes(StandardCharsets.UTF_8);
        if (body.length > LARGE_UPLOAD_BYTES) {
            log.warn("[triply] '{}' export is {} bytes -- above TriplyDB's ~5 MB graph-store guidance; " +
                    "if this fails, switch this upload to the jobs API", label, body.length);
        }
        HttpResponse<String> res = send(HttpRequest.newBuilder(graphStoreUri(label))
                .header("Content-Type", "application/n-triples")
                .timeout(Duration.ofMinutes(5))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
        expectOk(res, "PUT graph " + graphIri(label));
        log.info("[triply] pushed '{}' as <{}> ({} bytes)", label, graphIri(label), body.length);

        syncServices();
    }

    private void deleteGraph(String label) throws Exception {
        String iri = graphIri(label);
        // Graph listings are paginated via a `Link: <…>; rel="next"` header (no limit param).
        URI page = datasetUri("/graphs");
        while (page != null) {
            HttpResponse<String> res = send(HttpRequest.newBuilder(page).GET());
            if (res.statusCode() == 404) return; // dataset was never created -- nothing to delete
            expectOk(res, "list graphs");

            for (JsonNode g : objectMapper.readTree(res.body())) {
                if (!iri.equals(g.path("graphName").asText())) continue;
                HttpResponse<String> del = send(HttpRequest.newBuilder(
                        datasetUri("/graphs/" + URLEncoder.encode(g.path("id").asText(), StandardCharsets.UTF_8)))
                        .DELETE());
                expectOk(del, "DELETE graph " + iri);
                log.info("[triply] deleted <{}>", iri);
                syncServices();
                return;
            }
            page = nextPage(res);
        }
        log.info("[triply] <{}> not found in TriplyDB -- nothing to delete", iri);
    }

    /** Creates the dataset on first use, so a fresh account/dataset name works without UI setup. */
    private void ensureDataset() throws Exception {
        if (datasetReady) return;
        HttpResponse<String> res = send(HttpRequest.newBuilder(datasetUri("")).GET());
        if (res.statusCode() == 404) {
            String json = objectMapper.writeValueAsString(Map.of(
                    "name", dataset,
                    "displayName", "SK knowledge bases",
                    "description", "Knowledge graphs mirrored from sk-movies-be (one named graph per knowledge base).",
                    "accessLevel", accessLevel));
            HttpResponse<String> created = send(HttpRequest.newBuilder(
                    URI.create(API_URL + "/datasets/" + account))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)));
            expectOk(created, "create dataset " + account + "/" + dataset);
            log.info("[triply] created dataset {}/{}", account, dataset);
        } else {
            expectOk(res, "read dataset " + account + "/" + dataset);
        }
        datasetReady = true;
    }

    /**
     * SPARQL services don't see graph-store writes until synced. Datasets without a service
     * (the default -- one has to be added in the TriplyDB UI) simply have nothing to sync.
     */
    private void syncServices() {
        try {
            HttpResponse<String> res = send(HttpRequest.newBuilder(datasetUri("/services")).GET());
            expectOk(res, "list services");
            for (JsonNode s : objectMapper.readTree(res.body())) {
                String name = s.path("name").asText();
                if (name.isEmpty()) continue;
                HttpResponse<String> sync = send(HttpRequest.newBuilder(
                        datasetUri("/services/" + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"sync\":true}")));
                expectOk(sync, "sync service " + name);
            }
        } catch (Exception e) {
            // Non-fatal -- the graph itself is stored; only SPARQL freshness is affected.
            log.warn("[triply] service sync failed: {}", e.getMessage());
        }
    }

    // ── Neo4j → N-Triples ─────────────────────────────────────────────────────

    /**
     * Same node/edge scope as KnowledgeService#graph: every KGNode with this sourceLabel becomes
     * an IRI typed by its Neo4j labels (minus the KGNode marker), with rdfs:label = name and its
     * scalar properties as literals; every edge between two such nodes becomes a triple whose
     * predicate is the relationship type.
     */
    String exportNTriples(String label) {
        StringBuilder out = new StringBuilder();
        try (Session session = driver.session()) { // Neo4j
            session.run(
                    "MATCH (n:KGNode {sourceLabel: $l}) RETURN n.name AS name, labels(n) AS labels, properties(n) AS props",
                    Map.of("l", label)
            ).forEachRemaining(record -> {
                String subject = nodeIri(label, record.get("name").asString());
                triple(out, subject, RDFS_LABEL, literal(record.get("name").asString()));
                for (String type : record.get("labels").asList(org.neo4j.driver.Value::asString)) {
                    if (type.equals("KGNode")) continue;
                    triple(out, subject, RDF_TYPE, iri(vocab("class/" + type)));
                }
                record.get("props").asMap().forEach((key, value) -> {
                    if (SKIPPED_PROPS.contains(key) || value == null) return;
                    triple(out, subject, vocab("prop/" + key), typedLiteral(value));
                });
            });

            session.run(
                    "MATCH (a:KGNode {sourceLabel: $l})-[r]->(b:KGNode {sourceLabel: $l}) " +
                            "RETURN a.name AS source, b.name AS target, type(r) AS type",
                    Map.of("l", label)
            ).forEachRemaining(record -> triple(out,
                    nodeIri(label, record.get("source").asString()),
                    vocab("rel/" + record.get("type").asString()),
                    iri(nodeIri(label, record.get("target").asString()))));
        }
        return out.toString();
    }

    private static void triple(StringBuilder out, String subject, String predicate, String object) {
        out.append(iri(subject)).append(' ').append(iri(predicate)).append(' ').append(object).append(" .\n");
    }

    private static String iri(String value) {
        return "<" + value + ">";
    }

    private static String literal(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04X", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String typedLiteral(Object value) {
        if (value instanceof Boolean) return literal(value.toString()) + "^^" + iri(XSD + "boolean");
        if (value instanceof Long || value instanceof Integer) return literal(value.toString()) + "^^" + iri(XSD + "integer");
        if (value instanceof Double || value instanceof Float) return literal(value.toString()) + "^^" + iri(XSD + "double");
        if (value instanceof List<?> list) return literal(list.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse(""));
        return literal(String.valueOf(value));
    }

    // ── IRIs (TriplyDB's own /graphs, /id, /def convention for a dataset) ─────

    private String graphIri(String label) {
        return APP_URL + "/" + account + "/" + dataset + "/graphs/" + segment(label);
    }

    private String nodeIri(String label, String name) {
        return APP_URL + "/" + account + "/" + dataset + "/id/" + segment(label) + "/" + segment(name);
    }

    private String vocab(String path) {
        return APP_URL + "/" + account + "/" + dataset + "/def/" + segment(path).replace("%2F", "/");
    }

    private static String segment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private URI datasetUri(String suffix) {
        return URI.create(API_URL + "/datasets/" + account + "/" + dataset + suffix);
    }

    private URI graphStoreUri(String label) {
        return datasetUri("/graph-store?graph=" + URLEncoder.encode(graphIri(label), StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return httpClient.send(builder
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI nextPage(HttpResponse<String> res) {
        for (String link : res.headers().allValues("Link")) {
            for (String part : link.split(",")) {
                if (part.contains("rel=\"next\"")) {
                    int start = part.indexOf('<'), end = part.indexOf('>');
                    if (start >= 0 && end > start) return URI.create(part.substring(start + 1, end));
                }
            }
        }
        return null;
    }

    private static void expectOk(HttpResponse<String> res, String what) {
        if (res.statusCode() / 100 != 2) {
            String body = res.body() == null ? "" : res.body();
            throw new IllegalStateException("TriplyDB " + what + " -> HTTP " + res.statusCode() + ": "
                    + (body.length() > 500 ? body.substring(0, 500) + "…" : body));
        }
    }
}
