package com.moviesApp.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.MonitoredResource;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload.JsonPayload;
import com.google.cloud.logging.Severity;
import com.moviesApp.model.StructuredLogPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes structured application logs to Google Cloud Logging.
 * <p>
 * Mirrors the FE's logging module (sk-movies-fe/src/lib/utils/logger.ts) — a single shared
 * log stream, with entries tagged by an "origin" (client/server) field rather than split
 * across separate logs. Becomes a silent no-op when no Google credentials are available
 * (e.g. local dev) instead of failing app startup, same as the FE's `dev` guard.
 */
@Service
public class LoggingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingService.class);
    private static final int MAX_FIELD_LENGTH = 4000;
    private static final Path ROOT_CREDENTIALS = Path.of("credentials.json");

    private final Logging logging;
    private final String logName;
    private final ExecutorService executor;

    public LoggingService() {
        boolean disabled = isTruthy(System.getenv("DISABLE_STRUCTURED_LOGGING"));
        String environment = System.getenv().getOrDefault("LOG_ENVIRONMENT", "production");
        this.logName = "sk-movies-" + environment;
        this.logging = disabled ? null : buildClient();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "structured-logging");
            thread.setDaemon(true);
            return thread;
        });

        if (this.logging == null) {
            LOGGER.info(
                    "Structured logging to Google Cloud Logging is disabled "
                            + "(no credentials found, or DISABLE_STRUCTURED_LOGGING is set)");
        }
    }

    private static boolean isTruthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static Logging buildClient() {
        try {
            LoggingOptions.Builder options = LoggingOptions.newBuilder();
            // Prefer a root credentials.json when present, same convention as the FE.
            if (Files.exists(ROOT_CREDENTIALS)) {
                try (FileInputStream in = new FileInputStream(ROOT_CREDENTIALS.toFile())) {
                    options.setCredentials(GoogleCredentials.fromStream(in));
                }
            }
            // Otherwise falls back to Application Default Credentials (GOOGLE_APPLICATION_CREDENTIALS
            // env var, gcloud user creds, or an attached service account when running on GCP compute).
            return options.build().getService();
        } catch (Exception e) {
            // No credentials resolvable by any of the above — disable rather than crash startup.
            return null;
        }
    }

    public boolean isEnabled() {
        return logging != null;
    }

    public String ensureTraceId(String value) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return UUID.randomUUID().toString();
    }

    /** Fire-and-forget structured log write; never throws, never blocks the caller. */
    public void logStructuredError(StructuredLogPayload payload) {
        if (!isEnabled()) return;
        executor.submit(() -> writeEntry(payload));
    }

    /**
     * Synchronous write for the /test-gcp-logging diagnostic endpoint — unlike
     * {@link #logStructuredError}, this lets the caller see whether the write actually
     * succeeded instead of firing and forgetting.
     */
    public void writeTestEntry() throws Exception {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "Structured logging is disabled (no credentials found, or DISABLE_STRUCTURED_LOGGING is set)");
        }

        StructuredLogPayload payload = new StructuredLogPayload();
        payload.setMessage("Manual backend logging test");
        payload.setOrigin("server");
        payload.setSeverity("INFO");
        payload.setContext(Map.of(
                "service", "sk-movies-be",
                "environment", System.getenv().getOrDefault("LOG_ENVIRONMENT", "production"),
                "source", "test-endpoint",
                "ts", Instant.now().toString()));

        writeEntryUnsafe(payload);
    }

    /** Convenience for BE code logging its own exceptions (origin=server). */
    public void logServerError(String message, Throwable error, Map<String, Object> context) {
        StructuredLogPayload payload = new StructuredLogPayload();
        payload.setMessage(message != null ? message : (error != null ? error.getMessage() : "Unknown error"));
        payload.setOrigin("server");
        payload.setSeverity("ERROR");
        payload.setStack(error != null ? stackTraceToString(error) : null);
        payload.setContext(context);
        logStructuredError(payload);
    }

    private static String stackTraceToString(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > MAX_FIELD_LENGTH ? value.substring(0, MAX_FIELD_LENGTH) : value;
    }

    private static Severity toSeverity(String raw) {
        if (raw == null) return Severity.ERROR;
        try {
            return Severity.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Severity.ERROR;
        }
    }

    private void writeEntry(StructuredLogPayload payload) {
        try {
            writeEntryUnsafe(payload);
        } catch (Exception e) {
            LOGGER.warn("Failed to write structured log entry", e);
        }
    }

    private void writeEntryUnsafe(StructuredLogPayload payload) throws Exception {
        Severity severity = toSeverity(payload.getSeverity());

        Map<String, Object> jsonPayload = new HashMap<>();
        jsonPayload.put("message", truncate(payload.getMessage()));
        jsonPayload.put("severity", severity.name());
        jsonPayload.put("origin", payload.getOrigin());
        jsonPayload.put("path", payload.getPath());
        jsonPayload.put("url", payload.getUrl());
        jsonPayload.put("routeId", payload.getRouteId());
        jsonPayload.put("status", payload.getStatus());
        jsonPayload.put("userAgent", truncate(payload.getUserAgent()));
        jsonPayload.put("stack", truncate(payload.getStack()));
        if (payload.getContext() != null) {
            jsonPayload.put("context", payload.getContext());
        }
        jsonPayload.values().removeIf(v -> v == null);

        LogEntry.Builder entryBuilder = LogEntry.newBuilder(JsonPayload.of(jsonPayload))
                .setSeverity(severity)
                .setResource(MonitoredResource.newBuilder("global").build());

        if (payload.getTraceId() != null) {
            entryBuilder.addLabel("requestTraceId", payload.getTraceId());
        }
        if (payload.getSessionCorrelationId() != null) {
            entryBuilder.addLabel("sessionCorrelationId", payload.getSessionCorrelationId());
        }

        logging.write(Collections.singleton(entryBuilder.build()), Logging.WriteOption.logName(logName));
    }
}
