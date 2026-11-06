package com.moviesApp.controllers;

import com.moviesApp.service.GrafanaMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Backend metrics for the /performance page. The client names a panel and a range; the PromQL is
 * fixed server-side (see {@link GrafanaMetricsService}).
 */
@RestController
@RequestMapping("/performance/metrics")
public class MetricsController {

    private final GrafanaMetricsService metrics;

    public MetricsController(GrafanaMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/{panel}")
    public ResponseEntity<Map<String, Object>> panel(
            @PathVariable String panel,
            @RequestParam(defaultValue = "1h") String range) {
        if (!GrafanaMetricsService.isKnownPanel(panel) || !GrafanaMetricsService.isKnownRange(range)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown panel or range"));
        }
        if (!metrics.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of("error", "Grafana metrics are not configured"));
        }
        try {
            return ResponseEntity.ok(metrics.query(panel, range));
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "upstream error"));
        }
    }
}
