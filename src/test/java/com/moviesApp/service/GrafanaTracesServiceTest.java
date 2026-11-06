package com.moviesApp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrafanaTracesServiceTest {

    private static final String ID = "858f3bc34505c24e351bcbdd28bf699d";

    private HttpServer server;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile String body = "{}";
    private volatile int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(URLDecoder.decode(ex.getRequestURI().toString(), StandardCharsets.UTF_8));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GrafanaTracesService service() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/tempo/";
        return new GrafanaTracesService(new ObjectMapper(), url, "42", "glc_secret", "sk-movies-be");
    }

    private static String span(String id, String parent, String name, long start, long end) {
        return "{\"spanId\":\"" + id + "\",\"parentSpanId\":\"" + parent + "\",\"name\":\"" + name + "\","
                + "\"startTimeUnixNano\":\"" + start + "\",\"endTimeUnixNano\":\"" + end + "\","
                + "\"attributes\":[{\"key\":\"http.request.method\",\"value\":{\"stringValue\":\"GET\"}}]}";
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsBasicAuthAndReadsTheList() throws Exception {
        body = "{\"traces\":[{\"traceID\":\"" + ID + "\",\"rootTraceName\":\"http get /getAllMovies\","
                + "\"startTimeUnixNano\":\"1793978172910404926\",\"durationMs\":105},"
                + "{\"traceID\":\"" + "a".repeat(32) + "\",\"startTimeUnixNano\":\"1793978272910404926\"}]}";
        Map<String, Object> out = service().list("recent", "1h");

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("42:glc_secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, lastAuth.get());
        assertTrue(requests.get(0).startsWith("/tempo/api/search?"), requests.get(0));
        assertTrue(requests.get(0).contains("resource.service.name = \"sk-movies-be\""));

        List<Map<String, Object>> traces = (List<Map<String, Object>>) out.get("traces");
        assertEquals(2, traces.size());
        // newest first, and a trace with no root name or duration still has sane values
        assertEquals("a".repeat(32), traces.get(0).get("traceId"));
        assertEquals("unknown", traces.get(0).get("name"));
        assertEquals(0L, traces.get(0).get("durationMs"));
        assertEquals(105L, traces.get(1).get("durationMs"));
    }

    @Test
    void slowListFiltersByDurationAndRecentSkipsUptimeProbes() throws Exception {
        body = "{\"traces\":[]}";
        service().list("slow", "6h");
        service().list("recent", "6h");
        assertTrue(requests.get(0).contains("duration > 500ms"));
        assertTrue(requests.get(1).contains("name !~ \"http head.*\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsAWaterfallInTreeOrderWithDepth() throws Exception {
        // child "b" starts before sibling "c"; "d" is a child of "c"; spans arrive out of order.
        body = "{\"trace\":{\"resourceSpans\":[{\"scopeSpans\":[{\"spans\":["
                + span("d", "c", "neo4j.query", 1_300_000_000L, 1_400_000_000L)
                + "," + span("a", "", "http get /x", 1_000_000_000L, 2_000_000_000L)
                + "," + span("c", "a", "service", 1_200_000_000L, 1_500_000_000L)
                + "," + span("b", "a", "filter", 1_100_000_000L, 1_150_000_000L)
                + "]}]}]}}";
        Map<String, Object> out = service().trace(ID);

        assertTrue(requests.get(0).startsWith("/tempo/api/v2/traces/" + ID));
        assertEquals(1000.0, out.get("durationMs"));
        List<Map<String, Object>> spans = (List<Map<String, Object>>) out.get("spans");
        assertEquals(List.of("a", "b", "c", "d"), spans.stream().map(s -> s.get("spanId")).toList());
        assertEquals(List.of(0, 1, 1, 2), spans.stream().map(s -> s.get("depth")).toList());
        assertEquals(200.0, spans.get(2).get("offsetMs"));
        assertEquals(300.0, spans.get(2).get("durationMs"));
        Map<String, String> attrs = (Map<String, String>) spans.get(0).get("attributes");
        assertEquals("GET", attrs.get("http.request.method"));
    }

    @Test
    void survivesAParentCycleWithoutLooping() throws Exception {
        body = "{\"trace\":{\"resourceSpans\":[{\"scopeSpans\":[{\"spans\":["
                + span("a", "b", "one", 1_000_000_000L, 2_000_000_000L) + ","
                + span("b", "a", "two", 1_100_000_000L, 1_900_000_000L) + "]}]}]}}";
        assertEquals(2, ((List<?>) service().trace(ID).get("spans")).size());
    }

    @Test
    void rejectsBadTraceIdsBeforeCallingUpstream() {
        for (String bad : List.of("", "short", "../etc/passwd", ID.toUpperCase(), ID + "0", ID.replace('8', 'z'))) {
            assertThrows(IllegalArgumentException.class, () -> service().trace(bad), bad);
        }
        assertTrue(requests.isEmpty());
        assertFalse(GrafanaTracesService.isValidTraceId(null));
    }

    @Test
    void unknownTraceIsReportedAsNotFound() {
        status = 404;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service().trace(ID));
        assertEquals("trace not found", e.getMessage());
    }

    @Test
    void upstreamErrorDoesNotEchoTheBody() {
        status = 500;
        body = "secret internal detail";
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service().list("recent", "1h"));
        assertEquals("Grafana Tempo API returned HTTP 500", e.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> points(Map<String, Object> out) {
        return (List<List<Object>>) ((Map<String, Object>) ((List<?>) out.get("series")).get(0)).get("points");
    }

    @Test
    void spanRateTreatsMissingValuesAsZeroAndP95ConvertsToMs() throws Exception {
        body = "{\"series\":[{\"samples\":[{\"timestampMs\":\"1000000\"},{\"timestampMs\":\"1060000\",\"value\":0.5}]}]}";
        Map<String, Object> rate = service().series("span_rate", "1h");
        assertEquals(List.of(1000L, 0.0), points(rate).get(0));
        assertEquals(List.of(1060L, 0.5), points(rate).get(1));
        assertEquals("spans/s", rate.get("unit"));

        body = "{\"series\":[{\"samples\":[{\"timestampMs\":\"1000000\"},{\"timestampMs\":\"1060000\",\"value\":0.25}]}]}";
        Map<String, Object> p95 = service().series("span_p95", "1h");
        assertEquals(1, points(p95).size(), "a missing quantile is no data, not zero");
        assertEquals(250.0, points(p95).get(0).get(1));
        assertEquals("ms", p95.get("unit"));
    }

    @Test
    void wideRangesAreFetchedInWindowsTempoAccepts() throws Exception {
        body = "{\"series\":[]}";
        Map<String, Object> out = service().series("span_rate", "7d");
        assertEquals(8, requests.size(), "7d must be split into windows of at most 23h");
        assertEquals(List.of(), out.get("series"));
    }

    @Test
    void windowsCoverTheRangeWithoutGapsOrOversize() {
        List<long[]> w = GrafanaTracesService.windows(0, 7 * 86_400L, 23 * 3600L);
        assertEquals(0, w.get(0)[0]);
        assertEquals(7 * 86_400L, w.get(w.size() - 1)[1]);
        for (int i = 0; i < w.size(); i++) {
            assertTrue(w.get(i)[1] - w.get(i)[0] <= 23 * 3600L);
            if (i > 0) {
                assertEquals(w.get(i - 1)[1], w.get(i)[0]);
            }
        }
    }

    @Test
    void rejectsUnknownKindsPanelsAndRanges() {
        assertThrows(IllegalArgumentException.class, () -> service().list("all", "1h"));
        assertThrows(IllegalArgumentException.class, () -> service().list("recent", "100y"));
        assertThrows(IllegalArgumentException.class, () -> service().series("drop", "1h"));
        assertFalse(new GrafanaTracesService(new ObjectMapper(), "", "", "", "x").isConfigured());
    }
}
