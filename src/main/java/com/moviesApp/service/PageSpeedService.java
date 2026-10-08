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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin proxy for Google's PageSpeed Insights v5 API (Lighthouse-as-a-service). Lives server-side
 * rather than being called directly from the browser so the API key -- when one is configured --
 * never reaches client code. The key is optional: PSI accepts unauthenticated calls at a lower
 * quota, which is enough for interactive/dev use.
 */
@Service
public class PageSpeedService {

    private static final String API_URL = "https://www.googleapis.com/pagespeedonline/v5/runPagespeed";

    @Value("${pagespeed.api.key:}")
    private String apiKey;

    private final ObjectMapper objectMapper;
    private final BudgetService budgetService;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public PageSpeedService(ObjectMapper objectMapper, BudgetService budgetService) {
        this.objectMapper = objectMapper;
        this.budgetService = budgetService;
    }

    public Map<String, Object> runPagespeed(String url, String strategy) throws Exception {
        String effectiveStrategy = "desktop".equalsIgnoreCase(strategy) ? "desktop" : "mobile";

        StringBuilder uri = new StringBuilder(API_URL)
                .append("?url=").append(URLEncoder.encode(url, StandardCharsets.UTF_8))
                .append("&strategy=").append(effectiveStrategy)
                .append("&category=performance&category=accessibility&category=best-practices&category=seo");
        if (apiKey != null && !apiKey.isBlank()) {
            uri.append("&key=").append(apiKey);
        }

        // A full Lighthouse run (what this API does server-side on Google's end) routinely takes
        // 20-40s -- a short timeout here would misreport Google's own latency as our failure.
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(uri.toString()))
                .timeout(Duration.ofSeconds(90))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("PageSpeed API error " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode root       = objectMapper.readTree(resp.body());
        JsonNode lighthouse = root.path("lighthouseResult");
        JsonNode categories = lighthouse.path("categories");
        JsonNode audits     = lighthouse.path("audits");

        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("performance",   score(categories, "performance"));
        scores.put("accessibility", score(categories, "accessibility"));
        scores.put("bestPractices", score(categories, "best-practices"));
        scores.put("seo",           score(categories, "seo"));

        // Keyed by Lighthouse's own audit ids (not a friendlier camelCase) so BudgetService can
        // look a budget.json "metric" entry up directly, with no name-translation layer to get
        // out of sync.
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("first-contentful-paint",   auditNumeric(audits, "first-contentful-paint"));
        metrics.put("largest-contentful-paint", auditNumeric(audits, "largest-contentful-paint"));
        metrics.put("total-blocking-time",      auditNumeric(audits, "total-blocking-time"));
        metrics.put("cumulative-layout-shift",  auditNumeric(audits, "cumulative-layout-shift"));
        metrics.put("speed-index",              auditNumeric(audits, "speed-index"));
        metrics.put("interactive",              auditNumeric(audits, "interactive"));

        List<Map<String, Object>> resourceSummary = new ArrayList<>();
        for (JsonNode item : audits.path("resource-summary").path("details").path("items")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resourceType", item.path("resourceType").asText(""));
            row.put("requestCount", item.path("requestCount").asInt(0));
            row.put("transferSize", item.path("transferSize").asLong(0));
            resourceSummary.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url",             url);
        result.put("strategy",        effectiveStrategy);
        result.put("scores",          scores);
        result.put("metrics",         metrics);
        result.put("resourceSummary", resourceSummary);
        result.put("budget",          budgetService.evaluate(resourceSummary, metrics));
        return result;
    }

    // Lighthouse scores are 0-1 floats; convert to the familiar 0-100 integer.
    private Integer score(JsonNode categories, String key) {
        JsonNode s = categories.path(key).path("score");
        return s.isMissingNode() || s.isNull() ? null : Math.round((float) (s.asDouble() * 100));
    }

    private Double auditNumeric(JsonNode audits, String key) {
        JsonNode v = audits.path(key).path("numericValue");
        return v.isMissingNode() || v.isNull() ? null : v.asDouble();
    }
}
