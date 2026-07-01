package com.moviesApp.repositories;

import com.moviesApp.entities.CachedResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface CachedResponseRepository extends JpaRepository<CachedResponse, Long> {

    Optional<CachedResponse> findByCacheNameAndCacheKeyHash(String cacheName, String cacheKeyHash);

    @Modifying
    @Transactional
    void deleteByCacheName(String cacheName);

    @Modifying
    @Transactional
    void deleteByCacheNameAndCacheKeyHash(String cacheName, String cacheKeyHash);
}
