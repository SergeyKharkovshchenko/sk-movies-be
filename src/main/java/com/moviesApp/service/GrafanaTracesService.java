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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Read-only proxy to the Grafana Cloud Tempo query API, used by the /performance page.
 *
 * <p>Like {@link GrafanaMetricsService}, the browser never sends TraceQL: it names a list kind, a
 * chart panel or a trace id, and the queries are fixed here. Trace ids are validated before they
 * reach the upstream URL.
 */
@Service
public class GrafanaTracesService {

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    /** Tempo rejects metrics queries wider than 25h, so wider ranges are fetched in windows. */
    private static final long MAX_WINDOW_SECONDS = 23 * 3600L;
    /** The default Tempo search limit is 168h; stay just under it. */
    private static final long MAX_SEARCH_SECONDS = 167 * 3600L;
    private static final int LIST_LIMIT = 25;
    private static final int MAX_ATTRIBUTES = 24;
    private static final int MAX_ATTRIBUTE_LENGTH = 200;
    private static final Duration CACHE_TTL = Duration.ofSeconds(20);

    // Tempo leaves out the newest, incomplete step interval, so a coarse step hides the latest data;
    // keep steps small (a week at 15 min is still only ~92 samples per 23h window).
    record RangeSpec(long seconds, int step) {}

    private static final Map<String, RangeSpec> RANGES = new LinkedHashMap<>();
    static {
        RANGES.put("15m", new RangeSpec(15 * 60, 60));
        RANGES.put("1h", new RangeSpec(60 * 60, 60));
        RANGES.put("6h", new RangeSpec(6 * 60 * 60, 300));
        RANGES.put("24h", new RangeSpec(24 * 60 * 60, 300));
        RANGES.put("7d", new RangeSpec(7 * 24 * 60 * 60, 900));
    }

    private record CacheEntry(Instant at, Map<String, Object> value) {}

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String authHeader;
    private final String serviceName;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public GrafanaTracesService(
            ObjectMapper objectMapper,
            @Value("${grafana.tempo.url:}") String baseUrl,
            @Value("${grafana.tempo.user:}") String user,
            @Value("${grafana.tempo.token:}") String token,
            @Value("${grafana.tempo.service-name:sk-movies-be}") String serviceName) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
        this.serviceName = serviceName;
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }

    public static boolean isKnownRange(String range) {
        return RANGES.containsKey(range);
    }

    public static boolean isKnownListKind(String kind) {
        return "recent".equals(kind) || "slow".equals(kind);
    }

    public static boolean isKnownSeriesPanel(String panel) {
        return "span_rate".equals(panel) || "span_p95".equals(panel);
    }

    public static boolean isValidTraceId(String id) {
        return id != null && TRACE_ID.matcher(id).matches();
    }

    /** Splits [start, end] into consecutive windows no wider than {@code max} seconds. */
    static List<long[]> windows(long start, long end, long max) {
        List<long[]> out = new ArrayList<>();
        for (long from = start; from < end; from += max) {
            out.add(new long[] {from, Math.min(end, from + max)});
        }
        return out;
    }

    private String serverSpans() {
        // Quote characters cannot appear in the configured name from the allow-listed deployment, but
        // strip them anyway so a bad property can never break out of the TraceQL string.
        String safe = serviceName.replace("\"", "").replace("\\", "");
        return "resource.service.name = \"" + safe + "\" && kind = server";
    }

    // ---------------------------------------------------------------- list

    /** Returns { kind, range, from, to, traces: [{traceId, name, startMs, durationMs}] }, newest first. */
    public Map<String, Object> list(String kind, String rangeKey) throws Exception {
        RangeSpec range = RANGES.get(rangeKey);
        if (range == null || !isKnownListKind(kind)) {
            throw new IllegalArgumentException("unknown kind or range");
        }
        String cacheKey = "list|" + kind + "|" + rangeKey;
        Map<String, Object> cached = cached(cacheKey);
        if (cached != null) {
            return cached;
        }

        long end = Instant.now().getEpochSecond();
        long start = end - Math.min(range.seconds(), MAX_SEARCH_SECONDS);
        // Uptime probes (HEAD /**) would otherwise fill the "recent" list with 3 ms noise.
        String traceql = "slow".equals(kind)
                ? "{ " + serverSpans() + " && duration > 500ms }"
                : "{ " + serverSpans() + " && name !~ \"http head.*\" }";

        JsonNode root = get("/api/search",
                "q=" + enc(traceql) + "&start=" + start + "&end=" + end + "&limit=" + LIST_LIMIT);

        List<Map<String, Object>> traces = new ArrayList<>();
        for (JsonNode t : root.path("traces")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("traceId", t.path("traceID").asText());
            row.put("name", t.path("rootTraceName").asText("unknown"));
            row.put("startMs", t.path("startTimeUnixNano").asLong() / 1_000_000L);
            row.put("durationMs", t.path("durationMs").asLong(0));
            traces.add(row);
        }
        traces.sort(Comparator.comparingLong((Map<String, Object> r) -> (long) r.get("startMs")).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("range", rangeKey);
        out.put("from", start);
        out.put("to", end);
        out.put("traces", traces);
        return remember(cacheKey, out);
    }

    // ---------------------------------------------------------------- one trace

    /**
     * Returns { traceId, durationMs, spans: [{spanId, parentId, name, depth, offsetMs, durationMs,
     * error, attributes}] } ordered the way a waterfall reads (parent before children, by start time).
     */
    public Map<String, Object> trace(String traceId) throws Exception {
        if (!isValidTraceId(traceId)) {
            throw new IllegalArgumentException("invalid trace id");
        }
        JsonNode root = get("/api/v2/traces/" + traceId, null);
        JsonNode trace = root.has("trace") ? root.get("trace") : root;

        record Raw(String id, String parent, String name, long start, long end, boolean error,
                   Map<String, String> attributes) {}

        List<Raw> raw = new ArrayList<>();
        for (JsonNode rs : trace.path("resourceSpans")) {
            for (JsonNode ss : rs.path("scopeSpans")) {
                for (JsonNode s : ss.path("spans")) {
                    String parent = s.path("parentSpanId").asText("");
                    raw.add(new Raw(
                            s.path("spanId").asText(),
                            parent.isEmpty() ? null : parent,
                            s.path("name").asText("span"),
                            s.path("startTimeUnixNano").asLong(),
                            s.path("endTimeUnixNano").asLong(),
                            "STATUS_CODE_ERROR".equals(s.path("status").path("code").asText()),
                            attributes(s.path("attributes"))));
                }
            }
        }
        if (raw.isEmpty()) {
            throw new IllegalStateException("trace not found");
        }

        long traceStart = raw.stream().mapToLong(Raw::start).min().orElse(0);
        long traceEnd = raw.stream().mapToLong(Raw::end).max().orElse(traceStart);

        Map<String, Raw> byId = new HashMap<>();
        raw.forEach(r -> byId.put(r.id(), r));
        Map<String, Integer> depth = new HashMap<>();
        for (Raw r : raw) {
            int d = 0;
            String p = r.parent();
            int guard = 0; // a corrupt parent cycle must not loop forever
            while (p != null && byId.containsKey(p) && guard++ < 64) {
                d++;
                p = byId.get(p).parent();
            }
            depth.put(r.id(), d);
        }

        // Order depth-first so children sit right under their parent, siblings by start time.
        Map<String, List<Raw>> children = new TreeMap<>();
        List<Raw> roots = new ArrayList<>();
        for (Raw r : raw) {
            if (r.parent() != null && byId.containsKey(r.parent())) {
                children.computeIfAbsent(r.parent(), k -> new ArrayList<>()).add(r);
            } else {
                roots.add(r);
            }
        }
        Comparator<Raw> byStart = Comparator.comparingLong(Raw::start);
        roots.sort(byStart);
        children.values().forEach(l -> l.sort(byStart));

        List<Map<String, Object>> spans = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Raw> stack = new java.util.ArrayDeque<>();
        // Pass 1 walks the real roots. Pass 2 picks up anything unreachable from them (spans whose
        // parent links form a cycle) so corrupt data still renders instead of coming back empty.
        for (int pass = 0; pass < 2; pass++) {
            List<Raw> starts = pass == 0
                    ? roots
                    : raw.stream().filter(r -> !seen.contains(r.id())).sorted(byStart).toList();
            for (int i = starts.size() - 1; i >= 0; i--) {
                stack.push(starts.get(i));
            }
            while (!stack.isEmpty()) {
                Raw r = stack.pop();
                if (!seen.add(r.id())) {
                    continue;
                }
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("spanId", r.id());
                s.put("parentId", r.parent());
                s.put("name", r.name());
                s.put("depth", depth.get(r.id()));
                s.put("offsetMs", round((r.start() - traceStart) / 1_000_000.0));
                s.put("durationMs", round((r.end() - r.start()) / 1_000_000.0));
                s.put("error", r.error());
                s.put("attributes", r.attributes());
                spans.add(s);
                List<Raw> kids = children.getOrDefault(r.id(), List.of());
                for (int i = kids.size() - 1; i >= 0; i--) {
                    stack.push(kids.get(i));
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceId", traceId);
        out.put("durationMs", round((traceEnd - traceStart) / 1_000_000.0));
        out.put("spans", spans);
        return out;
    }

    private static Map<String, String> attributes(JsonNode attrs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode a : attrs) {
            if (out.size() >= MAX_ATTRIBUTES) {
                break;
            }
            JsonNode v = a.path("value");
            String text = v.has("stringValue") ? v.get("stringValue").asText()
                    : v.has("intValue") ? v.get("intValue").asText()
                    : v.has("doubleValue") ? v.get("doubleValue").asText()
                    : v.has("boolValue") ? v.get("boolValue").asText()
                    : null;
            if (text == null) {
                continue;
            }
            if (text.length() > MAX_ATTRIBUTE_LENGTH) {
                text = text.substring(0, MAX_ATTRIBUTE_LENGTH) + "…";
            }
            out.put(a.path("key").asText(), text);
        }
        return out;
    }

    // ---------------------------------------------------------------- charts from traces

    /**
     * Returns the same shape as {@link GrafanaMetricsService#query} so the page can reuse its chart:
     * { panel, unit, kind: "series", range, step, from, to, series: [{label, points: [[epochSec, value]]}] }.
     */
    public Map<String, Object> series(String panel, String rangeKey) throws Exception {
        RangeSpec range = RANGES.get(rangeKey);
        if (range == null || !isKnownSeriesPanel(panel)) {
            throw new IllegalArgumentException("unknown panel or range");
        }
        String cacheKey = "series|" + panel + "|" + rangeKey;
        Map<String, Object> cached = cached(cacheKey);
        if (cached != null) {
            return cached;
        }

        boolean rate = "span_rate".equals(panel);
        String traceql = "{ " + serverSpans() + " } | "
                + (rate ? "rate()" : "quantile_over_time(duration, .95)");

        long end = Instant.now().getEpochSecond();
        long start = end - range.seconds();

        List<CompletableFuture<List<List<Object>>>> parts = new ArrayList<>();
        for (long[] w : windows(start, end, MAX_WINDOW_SECONDS)) {
            parts.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return metricsWindow(traceql, w[0], w[1], range.step(), rate);
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }));
        }

        // Windows share their boundary timestamp; a TreeMap keeps one point per timestamp, in order.
        TreeMap<Long, Double> merged = new TreeMap<>();
        try {
            for (CompletableFuture<List<List<Object>>> part : parts) {
                for (List<Object> p : part.get()) {
                    merged.put((Long) p.get(0), (Double) p.get(1));
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getMessage(), cause);
        }

        List<List<Object>> points = new ArrayList<>();
        merged.forEach((t, v) -> points.add(List.of(t, v)));

        List<Map<String, Object>> series = new ArrayList<>();
        if (!points.isEmpty()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("label", rate ? "Spans" : "p95");
            s.put("points", points);
            series.add(s);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("panel", panel);
        out.put("unit", rate ? "spans/s" : "ms");
        out.put("kind", "series");
        out.put("range", rangeKey);
        out.put("step", range.step());
        out.put("from", start);
        out.put("to", end);
        out.put("series", series);
        return remember(cacheKey, out);
    }

    private List<List<Object>> metricsWindow(String traceql, long start, long end, int step, boolean rate)
            throws Exception {
        JsonNode root = get("/api/metrics/query_range",
                "q=" + enc(traceql) + "&start=" + start + "&end=" + end + "&step=" + step);
        List<List<Object>> points = new ArrayList<>();
        for (JsonNode series : root.path("series")) {
            for (JsonNode sample : series.path("samples")) {
                long ts = sample.path("timestampMs").asLong() / 1000L;
                if (!sample.has("value")) {
                    // protobuf-JSON omits zero: for a rate that is "no spans"; for a quantile it is "no data".
                    if (rate) {
                        points.add(List.of(ts, 0.0));
                    }
                    continue;
                }
                double v = sample.get("value").asDouble();
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    continue;
                }
                // Tempo reports durations in seconds; the page charts milliseconds.
                points.add(List.of(ts, round(rate ? v : v * 1000.0)));
            }
        }
        return points;
    }

    // ---------------------------------------------------------------- plumbing

    private JsonNode get(String path, String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path + (query == null ? "" : "?" + query)))
                .timeout(Duration.ofSeconds(25))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404 && path.startsWith("/api/v2/traces/")) {
            throw new IllegalStateException("trace not found");
        }
        if (res.statusCode() != 200) {
            // Deliberately omit the body and headers: nothing from the upstream (or the token) is echoed.
            throw new IllegalStateException("Grafana Tempo API returned HTTP " + res.statusCode());
        }
        return objectMapper.readTree(res.body());
    }

    private Map<String, Object> cached(String key) {
        CacheEntry hit = cache.get(key);
        if (hit != null && Duration.between(hit.at(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return hit.value();
        }
        return null;
    }

    private Map<String, Object> remember(String key, Map<String, Object> value) {
        cache.put(key, new CacheEntry(Instant.now(), value));
        return value;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
