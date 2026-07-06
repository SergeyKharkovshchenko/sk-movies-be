package com.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesApp.entities.CachedResponse;
import com.moviesApp.repositories.CachedResponseRepository;
import org.springframework.cache.Cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;

public class PostgresCache implements Cache {

    private final String                    name;
    private final CachedResponseRepository  repository;
    private final ObjectMapper              objectMapper;
    private final Class<?>                  valueType;
    private final Duration                  ttl; // null = never expires

    public PostgresCache(String name, CachedResponseRepository repository,
                         ObjectMapper objectMapper, Class<?> valueType, Duration ttl) {
        this.name         = name;
        this.repository   = repository;
        this.objectMapper = objectMapper;
        this.valueType    = valueType;
        this.ttl          = ttl;
    }

    @Override
    public String getName() { return name; }

    @Override
    public Object getNativeCache() { return repository; }

    @Override
    public ValueWrapper get(Object key) {
        Optional<CachedResponse> opt = repository.findByCacheNameAndCacheKeyHash(name, hash(key));
        if (opt.isEmpty()) return null;
        CachedResponse entry = opt.get();
        if (entry.getExpiresAt() != null && Instant.now().isAfter(entry.getExpiresAt())) {
            repository.delete(entry);
            return null;
        }
        try {
            Object value = objectMapper.readValue(entry.getValueJson(), valueType);
            return () -> value;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper w = get(key);
        return w != null ? (T) w.get() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper w = get(key);
        if (w != null) return (T) w.get();
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
    }

    @Override
    public void put(Object key, Object value) {
        try {
            String h = hash(key);
            CachedResponse entry = repository.findByCacheNameAndCacheKeyHash(name, h)
                    .orElse(new CachedResponse());
            entry.setCacheName(name);
            entry.setCacheKeyHash(h);
            entry.setValueJson(objectMapper.writeValueAsString(value));
            entry.setCreatedAt(Instant.now());
            entry.setExpiresAt(ttl != null ? Instant.now().plus(ttl) : null);
            repository.save(entry);
        } catch (Exception ignored) {
            // Cache write failure must never break the application
        }
    }

    @Override
    public void evict(Object key) {
        repository.deleteByCacheNameAndCacheKeyHash(name, hash(key));
    }

    @Override
    public void clear() {
        repository.deleteByCacheName(name); // PostgreSQL — deletes all rows for this cache name
    }

    // SHA-256 of the key string — fixed 64-char hex, safe to index
    private String hash(Object key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
