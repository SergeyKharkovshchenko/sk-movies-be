package com.moviesApp.config;

import com.moviesApp.model.StructuredLogPayload;
import com.moviesApp.service.LoggingService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Writes one structured log entry to Cloud Logging once the app has fully started — a quick
 * way to confirm structured logging is actually wired up on a given deploy (Render, local,
 * etc.) without having to trigger a real error or call /logs manually. No-ops the same way
 * LoggingService does everywhere else when no Google credentials are available.
 */
@Component
public class StartupLogger {

    private final LoggingService loggingService;

    public StartupLogger(LoggingService loggingService) {
        this.loggingService = loggingService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String environment = System.getenv().getOrDefault("LOG_ENVIRONMENT", "production");

        StructuredLogPayload payload = new StructuredLogPayload();
        payload.setMessage("sk-movies-be started");
        payload.setOrigin("server");
        payload.setSeverity("INFO");
        payload.setContext(Map.of(
                "service", "sk-movies-be",
                "environment", environment,
                "source", "startup"));

        loggingService.logStructuredError(payload);
    }
}
