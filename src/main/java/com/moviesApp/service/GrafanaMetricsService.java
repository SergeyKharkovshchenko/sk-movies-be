package com.moviesApp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only proxy to the Grafana Cloud Prometheus query API, used by the /performance page.
 *
 * <p>Only a fixed allow-list of panels and ranges can be queried: the browser sends a panel key,
 * never PromQL, so it cannot run arbitrary queries against the stack. The read token stays on the
 * server. Results are cached briefly so a page full of panels (and several viewers) does not turn
 * into a burst of upstream queries.
 */
@Service
public class GrafanaMetricsService {

    /** One PromQL query. The series label comes from {@code labelKey} when set, else {@code label}. */
    record Query(String label, String labelKey, String promql) {
        static Query fixed(String label, String promql) {
            return new Query(label, null, promql);
        }

        static Query byLabel(String labelKey, String promql) {
            return new Query(null, labelKey, promql);
        }
    }

    /** A panel is either a single number ("stat") or a set of time series. */
    record Panel(String unit, boolean stat, List<Query> queries) {}

    /** Resolution of a selectable time range. */
    record RangeSpec(long seconds, int stepSeconds, String rateWindow, String promRange) {}

    // OTLP pushes every 30s, so rate windows stay comfortably above two scrape intervals.
    private static final Map<String, RangeSpec> RANGES = new LinkedHashMap<>();
    static {
        RANGES.put("15m", new RangeSpec(15 * 60, 15, "1m", "15m"));
        RANGES.put("1h", new RangeSpec(60 * 60, 30, "2m", "1h"));
        RANGES.put("6h", new RangeSpec(6 * 60 * 60, 120, "5m", "6h"));
        RANGES.put("24h", new RangeSpec(24 * 60 * 60, 300, "10m", "24h"));
        RANGES.put("7d", new RangeSpec(7 * 24 * 60 * 60, 1800, "1h", "7d"));
    }

    private static final String REQ = "http_server_requests_milliseconds";

    // {{w}} = rate window, {{r}} = the selected range (for increase / last_over_time).
    private static final Map<String, Panel> PANELS = new LinkedHashMap<>();
    static {
        PANELS.put("uptime", new Panel("ms", true, List.of(Query.fixed("Uptime",
                "max(last_over_time(process_uptime_milliseconds[{{r}}]))"))));
        PANELS.put("requests_total", new Panel("requests", true, List.of(Query.fixed("Requests",
                "sum(increase(" + REQ + "_count[{{r}}]))"))));
        PANELS.put("error_rate_5xx", new Panel("%", true, List.of(Query.fixed("5xx",
                "100 * (sum(increase(" + REQ + "_count{status=~\"5..\"}[{{r}}])) or vector(0))"
                        + " / sum(increase(" + REQ + "_count[{{r}}]))"))));
        PANELS.put("heap_used", new Panel("bytes", true, List.of(Query.fixed("Heap used",
                "sum(last_over_time(jvm_memory_used_bytes{area=\"heap\"}[{{r}}]))"))));

        PANELS.put("requests_by_endpoint", new Panel("req/s", false, List.of(Query.byLabel("uri",
                "sum by (uri) (rate(" + REQ + "_count[{{w}}]))"))));
        PANELS.put("latency_p95_by_endpoint", new Panel("ms", false, List.of(Query.byLabel("uri",
                "histogram_quantile(0.95, sum by (le, uri) (rate(" + REQ + "_bucket[{{w}}])))"))));
        PANELS.put("responses_by_status", new Panel("req/s", false, List.of(Query.byLabel("status",
                "sum by (status) (rate(" + REQ + "_count[{{w}}]))"))));
        PANELS.put("jvm_heap", new Panel("bytes", false, List.of(Query.byLabel("id",
                "sum by (id) (jvm_memory_used_bytes{area=\"heap\"})"))));
        PANELS.put("cpu", new Panel("%", false, List.of(
                Query.fixed("Process", "100 * process_cpu_usage"),
                Query.fixed("System", "100 * system_cpu_usage"))));
        PANELS.put("db_connections", new Panel("connections", false, List.of(
                Query.fixed("Active", "hikaricp_connections_active"),
                Query.fixed("Idle", "hikaricp_connections_idle"),
                Query.fixed("Pending", "hikaricp_connections_pending"),
                Query.fixed("Max", "hikaricp_connections_max"))));
        PANELS.put("threads", new Panel("threads", false, List.of(
                Query.fixed("Live", "jvm_threads_live"),
                Query.fixed("Daemon", "jvm_threads_daemon"),
                Query.fixed("Peak", "jvm_threads_peak"))));
    }

    private static final Duration CACHE_TTL = Duration.ofSeconds(20);

    private record CacheEntry(Instant at, Map<String, Object> value) {}

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String authHeader;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public GrafanaMetricsService(
            ObjectMapper objectMapper,
            @Value("${grafana.prom.url:}") String baseUrl,
            @Value("${grafana.prom.user:}") String user,
            @Value("${grafana.prom.token:}") String token) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }

    public static boolean isKnownPanel(String panel) {
        return PANELS.containsKey(panel);
    }

    public static boolean isKnownRange(String range) {
        return RANGES.containsKey(range);
    }

    /**
     * Returns { panel, unit, kind: "stat"|"series", range, step, series: [{label, points:[[epochSec, value]]}] }.
     * For a stat the series has a single one-point entry.
     */
    public Map<String, Object> query(String panelKey, String rangeKey) throws Exception {
        Panel panel = PANELS.get(panelKey);
        RangeSpec range = RANGES.get(rangeKey);
        if (panel == null || range == null) {
            throw new IllegalArgumentException("unknown panel or range");
        }

        String cacheKey = panelKey + "|" + rangeKey;
        CacheEntry hit = cache.get(cacheKey);
        if (hit != null && Duration.between(hit.at(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return hit.value();
        }

        long end = Instant.now().getEpochSecond();
        long start = end - range.seconds();

        List<Map<String, Object>> series = new ArrayList<>();
        for (Query q : panel.queries()) {
            String promql = q.promql()
                    .replace("{{w}}", range.rateWindow())
                    .replace("{{r}}", range.promRange());
            JsonNode result = panel.stat()
                    ? instant(promql, end)
                    : rangeQuery(promql, start, end, range.stepSeconds());
            series.addAll(toSeries(result, q, panel.stat()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("panel", panelKey);
        out.put("unit", panel.unit());
        out.put("kind", panel.stat() ? "stat" : "series");
        out.put("range", rangeKey);
        out.put("step", range.stepSeconds());
        out.put("series", series);
        cache.put(cacheKey, new CacheEntry(Instant.now(), out));
        return out;
    }

    private JsonNode instant(String promql, long time) throws Exception {
        return call("/api/v1/query", "query=" + enc(promql) + "&time=" + time);
    }

    private JsonNode rangeQuery(String promql, long start, long end, int step) throws Exception {
        return call("/api/v1/query_range",
                "query=" + enc(promql) + "&start=" + start + "&end=" + end + "&step=" + step);
    }

    private JsonNode call(String path, String form) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            // Deliberately omit the body and headers: nothing from the upstream (or the token) is echoed.
            throw new IllegalStateException("Grafana Prometheus API returned HTTP " + res.statusCode());
        }
        JsonNode root = objectMapper.readTree(res.body());
        if (!"success".equals(root.path("status").asText())) {
            throw new IllegalStateException("Grafana Prometheus API reported an error");
        }
        return root.path("data").path("result");
    }

    private static List<Map<String, Object>> toSeries(JsonNode result, Query q, boolean instant) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : result) {
            JsonNode metric = item.path("metric");
            String label = q.labelKey() != null
                    ? metric.path(q.labelKey()).asText(q.labelKey())
                    : q.label();

            List<List<Object>> points = new ArrayList<>();
            if (instant) {
                addPoint(points, item.path("value"));
            } else {
                for (JsonNode v : item.path("values")) {
                    addPoint(points, v);
                }
            }
            if (points.isEmpty()) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("label", label);
            s.put("points", points);
            out.add(s);
        }
        return out;
    }

    /** Prometheus sends values as strings; NaN / Inf (e.g. p95 with no traffic) are dropped. */
    private static void addPoint(List<List<Object>> points, JsonNode pair) {
        if (!pair.isArray() || pair.size() < 2) {
            return;
        }
        double value;
        try {
            value = Double.parseDouble(pair.get(1).asText());
        } catch (NumberFormatException e) {
            return;
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return;
        }
        double rounded = Math.round(value * 10000.0) / 10000.0;
        points.add(List.of(pair.get(0).asLong(), rounded));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
