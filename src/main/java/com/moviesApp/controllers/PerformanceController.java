package com.moviesApp.controllers;

import com.moviesApp.service.PageSpeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/performance")
public class PerformanceController {

    private final PageSpeedService pageSpeedService;

    public PerformanceController(PageSpeedService pageSpeedService) {
        this.pageSpeedService = pageSpeedService;
    }

    /**
     * Proxies Google's PageSpeed Insights v5 API server-side (keeps the API key, when configured,
     * out of client code) and evaluates the result against resources/budget.json.
     * Returns: { url, strategy, scores: {performance, accessibility, bestPractices, seo},
     *            metrics: {<lighthouse-audit-id>: number}, resourceSummary: [...], budget: [...] }
     */
    @GetMapping("/pagespeed")
    public ResponseEntity<Map<String, Object>> pagespeed(
            @RequestParam String url,
            @RequestParam(defaultValue = "mobile") String strategy) {
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }
        try {
            return ResponseEntity.ok(pageSpeedService.runPagespeed(url, strategy));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }
}
