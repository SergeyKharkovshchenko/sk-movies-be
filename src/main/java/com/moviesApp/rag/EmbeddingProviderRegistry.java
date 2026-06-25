package com.moviesApp.rag;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Collects all EmbeddingProvider beans and resolves them by name at runtime,
// so swapping providers (or adding new ones) requires no changes to service code.
@Service
public class EmbeddingProviderRegistry {

    private final Map<String, EmbeddingProvider> providers;

    public EmbeddingProviderRegistry(List<EmbeddingProvider> all) {
        this.providers = all.stream()
                .collect(Collectors.toMap(EmbeddingProvider::getId, p -> p));
    }

    public EmbeddingProvider resolve(String name) {
        EmbeddingProvider p = providers.get(name);
        if (p == null) {
            throw new IllegalArgumentException(
                    "Unknown embedder: '" + name + "'. Available: " + providers.keySet());
        }
        return p;
    }

    public Set<String> available() {
        return providers.keySet();
    }
}
