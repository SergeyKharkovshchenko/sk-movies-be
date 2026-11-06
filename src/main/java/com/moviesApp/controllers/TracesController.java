package com.moviesApp.controllers;

import com.moviesApp.service.GrafanaTracesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Traces for the /performance page. The client names a list kind, a chart panel or a trace id; the
 * TraceQL is fixed server-side (see {@link GrafanaTracesService}).
 */
@RestController
@RequestMapping("/performance/traces")
public class TracesController {

    private final GrafanaTracesService traces;

    public TracesController(GrafanaTracesService traces) {
        this.traces = traces;
    }

    /** kind = recent | slow */
    @GetMapping("/list/{kind}")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String kind, @RequestParam(defaultValue = "1h") String range) {
        if (!GrafanaTracesService.isKnownListKind(kind) || !GrafanaTracesService.isKnownRange(range)) {
            return bad("unknown kind or range");
        }
        return respond(() -> traces.list(kind, range));
    }

    /** panel = span_rate | span_p95 */
    @GetMapping("/series/{panel}")
    public ResponseEntity<Map<String, Object>> series(
            @PathVariable String panel, @RequestParam(defaultValue = "1h") String range) {
        if (!GrafanaTracesService.isKnownSeriesPanel(panel) || !GrafanaTracesService.isKnownRange(range)) {
            return bad("unknown panel or range");
        }
        return respond(() -> traces.series(panel, range));
    }

    @GetMapping("/id/{traceId}")
    public ResponseEntity<Map<String, Object>> trace(@PathVariable String traceId) {
        if (!GrafanaTracesService.isValidTraceId(traceId)) {
            return bad("invalid trace id");
        }
        return respond(() -> traces.trace(traceId));
    }

    private ResponseEntity<Map<String, Object>> respond(Callable<Map<String, Object>> call) {
        if (!traces.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of("error", "Grafana traces are not configured"));
        }
        try {
            return ResponseEntity.ok(call.call());
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : "upstream error";
            return ResponseEntity.status("trace not found".equals(message) ? 404 : 502)
                    .body(Map.of("error", message));
        }
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
