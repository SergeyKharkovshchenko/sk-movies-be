package com.moviesApp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a PageSpeed Insights result against resources/budget.json, the same performance
 * budget format Lighthouse CLI's own --budget-path flag consumes (resourceSizes in KB,
 * resourceCounts, timings in ms except cumulative-layout-shift which is unitless) -- see
 * https://web.dev/articles/fast/performance-budgets-101. Evaluated here against the resource
 * breakdown and timing metrics the same PageSpeedService call already returned, rather than
 * shelling out to a separate Lighthouse CLI run.
 */
@Service
public class BudgetService {

    private final JsonNode budgetConfig;

    public BudgetService(ObjectMapper objectMapper) {
        JsonNode loaded;
        try {
            loaded = objectMapper.readTree(new ClassPathResource("budget.json").getInputStream());
        } catch (Exception e) {
            loaded = objectMapper.createArrayNode();
        }
        this.budgetConfig = loaded;
    }

    public List<Map<String, Object>> evaluate(List<Map<String, Object>> resourceSummary,
                                               Map<String, Object> metrics) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (!budgetConfig.isArray() || budgetConfig.isEmpty()) return results;

        Map<String, Long> sizeByType     = new HashMap<>();
        Map<String, Integer> countByType = new HashMap<>();
        for (Map<String, Object> row : resourceSummary) {
            String type = String.valueOf(row.get("resourceType"));
            sizeByType.put(type, ((Number) row.get("transferSize")).longValue());
            countByType.put(type, ((Number) row.get("requestCount")).intValue());
        }

        JsonNode budget = budgetConfig.get(0);
        for (JsonNode sizeRule : budget.path("resourceSizes")) {
            String type = sizeRule.path("resourceType").asText();
            double budgetKb = sizeRule.path("budget").asDouble();
            double actualKb = sizeByType.getOrDefault(type, 0L) / 1024.0;
            results.add(row("size", type, budgetKb, actualKb));
        }
        for (JsonNode countRule : budget.path("resourceCounts")) {
            String type = countRule.path("resourceType").asText();
            double budgetCount = countRule.path("budget").asDouble();
            double actualCount = countByType.getOrDefault(type, 0);
            results.add(row("count", type, budgetCount, actualCount));
        }
        for (JsonNode timingRule : budget.path("timings")) {
            // Metric names here are Lighthouse's own audit ids (e.g. "first-contentful-paint",
            // "interactive") -- PageSpeedService's `metrics` map is keyed by those exact same
            // ids, so no name translation is needed between budget.json and the live result.
            String metric = timingRule.path("metric").asText();
            double budgetValue = timingRule.path("budget").asDouble();
            Object actual = metrics.get(metric);
            double actualValue = actual == null ? -1 : ((Number) actual).doubleValue();
            results.add(row("timing", metric, budgetValue, actualValue));
        }
        return results;
    }

    private Map<String, Object> row(String kind, String name, double budget, double actual) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", kind);
        row.put("name", name);
        row.put("budget", budget);
        row.put("actual", actual);
        row.put("passed", actual >= 0 && actual <= budget);
        return row;
    }
}
