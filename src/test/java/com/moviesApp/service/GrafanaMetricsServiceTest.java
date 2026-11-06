package com.moviesApp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrafanaMetricsServiceTest {

    private HttpServer server;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile String responseJson;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GrafanaMetricsService service() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/prom/";
        return new GrafanaMetricsService(new ObjectMapper(), url, "123", "glc_secret");
    }

    @Test
    void sendsBasicAuthBuiltFromUserAndToken() throws Exception {
        responseJson = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";
        service().query("cpu", "1h");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("123:glc_secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, lastAuth.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesSeriesLabelsAndDropsNaN() throws Exception {
        responseJson = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"uri\":\"/getAllMovies\"},\"values\":[[100,\"1.5\"],[130,\"NaN\"],[160,\"2\"]]}]}}";
        Map<String, Object> out = service().query("latency_p95_by_endpoint", "1h");

        assertEquals("series", out.get("kind"));
        assertEquals("ms", out.get("unit"));
        assertEquals(3600L, (long) out.get("to") - (long) out.get("from"));
        List<Map<String, Object>> series = (List<Map<String, Object>>) out.get("series");
        assertEquals(1, series.size());
        assertEquals("/getAllMovies", series.get(0).get("label"));
        assertEquals(2, ((List<?>) series.get(0).get("points")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void statPanelUsesInstantQueryAndReturnsOnePoint() throws Exception {
        responseJson = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{},\"value\":[200,\"42\"]}]}}";
        Map<String, Object> out = service().query("requests_total", "24h");

        assertEquals("stat", out.get("kind"));
        assertTrue(lastBody.get().contains("query=") && lastBody.get().contains("time="));
        assertFalse(lastBody.get().contains("step="));
        List<Map<String, Object>> series = (List<Map<String, Object>>) out.get("series");
        assertEquals("Requests", series.get(0).get("label"));
    }

    @Test
    void cachesRepeatedQueries() throws Exception {
        responseJson = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";
        GrafanaMetricsService svc = service();
        svc.query("threads", "1h");
        int after = calls.get();
        svc.query("threads", "1h");
        assertEquals(after, calls.get());
    }

    @Test
    void rejectsUnknownPanelAndRange() {
        assertThrows(IllegalArgumentException.class, () -> service().query("drop_table", "1h"));
        assertThrows(IllegalArgumentException.class, () -> service().query("cpu", "100y"));
        assertFalse(GrafanaMetricsService.isKnownPanel("up{job=~'.*'}"));
        assertTrue(GrafanaMetricsService.isKnownRange("7d"));
    }

    @Test
    void notConfiguredWhenUrlBlank() {
        assertFalse(new GrafanaMetricsService(new ObjectMapper(), "", "", "").isConfigured());
    }
}
