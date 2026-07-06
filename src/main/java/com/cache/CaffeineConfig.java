package com.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moviesApp.repositories.CachedResponseRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableCaching
public class CaffeineConfig {

    // parentChildRelationsCache: 5s TTL, short-lived — Caffeine in-memory is fine
    private static final String PARENT_CACHE_SPEC = "maximumSize=200,expireAfterWrite=5s,recordStats";

    // suggestGraph / suggestSections: 24h TTL, persist across restarts — backed by PostgreSQL
    private static final Map<String, Duration> POSTGRES_CACHES = Map.of(
            "suggestGraph",    Duration.ofHours(24),
            "suggestSections", Duration.ofHours(24)
    );

    @Bean
    public CacheManager cacheManager(ObjectMapper objectMapper, CacheTypeResolver cacheTypeResolver,
                                     CachedResponseRepository cachedResponseRepository) {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        Set<Cache> caches = new LinkedHashSet<>();

        // In-memory Caffeine cache — fast, no persistence needed for 5s TTL
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                Caffeine.from(PARENT_CACHE_SPEC).build();
        caches.add(new CompressedCaffeineCache("parentChildRelationsCache", nativeCache, objectMapper, cacheTypeResolver));

        // PostgreSQL-backed caches — survive server restarts, cleared via DELETE /knowledge/cache
        for (Map.Entry<String, Duration> entry : POSTGRES_CACHES.entrySet()) {
            Class<?> valueType = cacheTypeResolver.resolveClass(entry.getKey());
            caches.add(new PostgresCache(entry.getKey(), cachedResponseRepository, objectMapper, valueType, entry.getValue()));
        }

        cacheManager.setCaches(caches);
        return cacheManager;
    }

}
