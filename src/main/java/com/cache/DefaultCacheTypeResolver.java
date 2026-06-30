package com.cache;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DefaultCacheTypeResolver implements CacheTypeResolver {

    //cashe example: maps cache name -> target type used to decompress a cached value.
    // Class<?> can't express List<Relation>, so a decompressed hit deserializes as
    // List<LinkedHashMap> rather than List<Relation> (only matters above the 1KB compression threshold).
    private static final Map<String, Class<?>> CACHE_TYPES = Map.of(
            "parentChildRelationsCache", List.class,
            "suggestGraph",             Map.class,
            "suggestSections",          List.class
    );

    @Override
    public Class<?> resolveClass(String cacheName) {
        return CACHE_TYPES.getOrDefault(cacheName, Object.class);
    }

}
