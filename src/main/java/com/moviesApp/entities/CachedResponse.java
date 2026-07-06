package com.moviesApp.entities;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "kg_cache", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"cache_name", "cache_key_hash"})
}, indexes = {
        @Index(name = "idx_kg_cache_name_key", columnList = "cache_name,cache_key_hash")
})
public class CachedResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cache_name", nullable = false, length = 100)
    private String cacheName;

    // SHA-256 hex of the full cache key — fixed 64 chars, safe for indexing
    @Column(name = "cache_key_hash", nullable = false, length = 64)
    private String cacheKeyHash;

    @Column(name = "value_json", nullable = false, columnDefinition = "TEXT")
    private String valueJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // null = never expires
    @Column(name = "expires_at")
    private Instant expiresAt;

    public CachedResponse() {}

    public Long    getId()           { return id; }
    public String  getCacheName()    { return cacheName; }
    public void    setCacheName(String cacheName)       { this.cacheName = cacheName; }
    public String  getCacheKeyHash() { return cacheKeyHash; }
    public void    setCacheKeyHash(String cacheKeyHash) { this.cacheKeyHash = cacheKeyHash; }
    public String  getValueJson()    { return valueJson; }
    public void    setValueJson(String valueJson)       { this.valueJson = valueJson; }
    public Instant getCreatedAt()    { return createdAt; }
    public void    setCreatedAt(Instant createdAt)      { this.createdAt = createdAt; }
    public Instant getExpiresAt()    { return expiresAt; }
    public void    setExpiresAt(Instant expiresAt)      { this.expiresAt = expiresAt; }
}
