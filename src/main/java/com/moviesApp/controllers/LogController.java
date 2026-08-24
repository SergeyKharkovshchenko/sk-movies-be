package com.moviesApp.controllers;

import com.moviesApp.model.StructuredLogPayload;
import com.moviesApp.service.LogRateLimiter;
import com.moviesApp.service.LoggingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ingest endpoint for FE structured logs (see sk-movies-fe's src/lib/utils/logger.ts).
 * The FE ships as a static build (adapter-static) and can never hold Google credentials
 * itself, so it forwards error/log payloads here and this service does the actual write
 * to Cloud Logging via {@link LoggingService}.
 */
@RestController
public class LogController {

    @Autowired
    private LoggingService loggingService;

    @Autowired
    private LogRateLimiter rateLimiter;

    @PostMapping("/logs")
    public ResponseEntity<Map<String, String>> ingest(
            @RequestBody(required = false) StructuredLogPayload payload, HttpServletRequest request) {
        if (payload == null || payload.getMessage() == null || payload.getMessage().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "rejected"));
        }

        if (!rateLimiter.tryAcquire(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("status", "rate-limited"));
        }

        // Never trust origin/userAgent from the client body — set them from the actual request.
        payload.setOrigin("client");
        payload.setUserAgent(request.getHeader("User-Agent"));
        payload.setTraceId(loggingService.ensureTraceId(payload.getTraceId()));

        loggingService.logStructuredError(payload);

        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }

    /**
     * Manual diagnostic — writes one entry and reports whether it actually succeeded,
     * unlike POST /logs which is deliberately fire-and-forget.
     */
    @GetMapping("/test-gcp-logging")
    public ResponseEntity<Map<String, Object>> testGcpLogging(HttpServletRequest request) {
        if (!rateLimiter.tryAcquire(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("ok", false, "error", "rate-limited"));
        }

        try {
            loggingService.writeTestEntry();
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", String.valueOf(e)));
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
