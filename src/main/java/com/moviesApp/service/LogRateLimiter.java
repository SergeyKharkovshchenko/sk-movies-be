package com.moviesApp.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal per-key rate limit for the unauthenticated {@code /logs} ingest endpoint, so it
 * can't be used to flood Cloud Logging (and run up the bill) by anyone who finds the URL.
 * Counters expire on their own via Caffeine, so this never grows unbounded.
 */
@Component
public class LogRateLimiter {

    private static final int MAX_REQUESTS_PER_WINDOW = 30;

    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public boolean tryAcquire(String key) {
        AtomicInteger counter = counters.get(key, k -> new AtomicInteger(0));
        return counter.incrementAndGet() <= MAX_REQUESTS_PER_WINDOW;
    }
}
