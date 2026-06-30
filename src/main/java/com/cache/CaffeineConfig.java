package com.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableCaching
public class CaffeineConfig {

    //cashe example: cache name -> Caffeine spec string, parsed by Caffeine.from(...)
    private static final Map<String, String> CACHE_SPECS = Map.of(
            "parentChildRelationsCache", "maximumSize=200,expireAfterWrite=5s,recordStats",
            "suggestGraph",              "maximumSize=100,expireAfterWrite=24h,recordStats",
            "suggestSections",           "maximumSize=100,expireAfterWrite=24h,recordStats"
    );

    @Bean
    public CacheManager cacheManager(ObjectMapper objectMapper, CacheTypeResolver cacheTypeResolver) {
        //cashe example: SimpleCacheManager holds a fixed, pre-built set of named caches
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        Set<Cache> caches = new LinkedHashSet<>();
        for (Map.Entry<String, String> spec : CACHE_SPECS.entrySet()) {
            //cashe example: build the native Caffeine cache from a spec string
            com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                    Caffeine.from(spec.getValue()).build();
            //cashe example: wrap it so large values get compressed before storage
            caches.add(new CompressedCaffeineCache(spec.getKey(), nativeCache, objectMapper, cacheTypeResolver));
        }
        cacheManager.setCaches(caches);
        return cacheManager;
    }

}
