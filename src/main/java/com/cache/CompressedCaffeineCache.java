package com.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A specialized implementation of CaffeineCache that supports compression for large cache values.
 * This class compresses values before storing them in the cache if they exceed a defined threshold.
 * When retrieving values, it automatically decompresses them if they were compressed.
 */
//cashe example
public class CompressedCaffeineCache extends CaffeineCache {
    private static final Logger logger = LoggerFactory.getLogger(CompressedCaffeineCache.class);

    private final ObjectMapper objectMapper;
    private final CacheTypeResolver cacheTypeResolver;
    private static final String COMPRESSED_PREFIX = "COMPRESSED:";
    private static final int COMPRESSION_THRESHOLD = 1024; // 1KB

    public CompressedCaffeineCache(String name, Cache<Object, Object> cache, ObjectMapper objectMapper, CacheTypeResolver cacheTypeResolver) {
        super(name, cache);
        this.objectMapper = objectMapper;
        this.cacheTypeResolver = cacheTypeResolver;
    }

    // Raw lookup is untyped, so the only way to know what class to decompress into
    // is cacheTypeResolver's static name->type mapping (see getValueType()).
    @Override
    public ValueWrapper get(@NonNull Object key) {
        ValueWrapper wrapper = super.get(key);
        if (wrapper != null) {
            Object value = wrapper.get();
            if (value instanceof String valueString && valueString.startsWith(COMPRESSED_PREFIX)) {
                try {
                    Object decompressedValue = decompress(valueString, getValueType());
                    // wrap in a lambda rather than a SimpleValueWrapper to avoid an extra import;
                    // ValueWrapper is a single-method functional interface
                    return () -> decompressedValue;
                } catch (IOException e) {
                    logger.error("Failed to decompress cache value for key: {}", key, e);
                    throw new RuntimeException("Failed to decompress cache value", e);
                }
            }
        }
        return wrapper;
    }

    // Caller supplies the target type explicitly here, so decompression is exact
    // (unlike the untyped get(key) above, which depends on cacheTypeResolver).
    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(@NonNull Object key, @Nullable Class<T> type) {
        Object value = super.get(key, Object.class);
        if (value instanceof String stringValue && stringValue.startsWith(COMPRESSED_PREFIX)) {
            try {
                return (T) decompress(stringValue, type);
            } catch (IOException e) {
                logger.error("Failed to decompress cache value for key: {}", key, e);
                throw new RuntimeException("Failed to decompress cache value", e);
            }
        }
        return (T) value;
    }

    // Cache-aside path: super.get() invokes valueLoader on a miss and caches the result,
    // so a freshly-loaded value is plain (uncompressed) here; only a prior compressed
    // hit needs decompressing. Target type is inferred from valueLoader's #call() signature
    // since the loader doesn't carry an explicit Class<T>.
    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
        try {
            Object value = super.get(key, valueLoader);
            if (value instanceof String stringValue && stringValue.startsWith(COMPRESSED_PREFIX)) {
                Class<T> type = detectValueType(valueLoader);
                return (T) decompress(stringValue, type);
            }
            return (T) value;
        } catch (Exception e) {
            logger.error("Failed to get cache value for key: {}", key, e);
            throw new RuntimeException("Failed to get cache value", e);
        }
    }

    // Only compress when the serialized payload crosses COMPRESSION_THRESHOLD; small
    // values are stored as-is since gzip+base64 overhead would outweigh the savings.
    @Override
    public void put(@NonNull Object key, @Nullable Object value) {
        try {
            Object valueToStore = shouldCompress(value) ? compress(value) : value;
            super.put(key, valueToStore);
        } catch (IOException e) {
            logger.error("Failed to compress cache value for key: {}", key, e);
            throw new RuntimeException("Failed to compress cache value", e);
        }
    }

    private boolean shouldCompress(Object value) throws IOException {
        if (value == null) return false;
        byte[] bytes = objectMapper.writeValueAsBytes(value);
        return bytes.length > COMPRESSION_THRESHOLD;
    }

    // JSON (via Jackson) -> gzip -> base64 text, tagged with COMPRESSED_PREFIX so a plain
    // cached String that happens to look similar is never mistaken for a compressed entry.
    private String compress(Object value) throws IOException {
        byte[] jsonBytes = objectMapper.writeValueAsBytes(value);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOS = new GZIPOutputStream(baos)) {
            gzipOS.write(jsonBytes);
            gzipOS.close();
            String compressedValue = Base64.getEncoder().encodeToString(baos.toByteArray());
            return COMPRESSED_PREFIX + compressedValue;
        }
    }

    // Reverse of compress(): strip the prefix, base64-decode, gunzip, then let Jackson
    // rebuild the object. targetType must be a plain Class, so generics on the original
    // value (e.g. List<Relation>) are lost - see CacheTypeResolver for the tradeoff.
    private <T> T decompress(String compressedString, Class<T> targetType) throws IOException {
        String base64Value = compressedString.substring(COMPRESSED_PREFIX.length());
        byte[] compressedBytes = Base64.getDecoder().decode(base64Value);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedBytes);
             GZIPInputStream gzipIS = new GZIPInputStream(bais);
             ByteArrayOutputStream result = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int length;
            while ((length = gzipIS.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            return objectMapper.readValue(result.toByteArray(), targetType);
        }
    }

    // Used by the untyped get(key) overload, which has no caller-supplied type to fall
    // back on; resolves by this cache's own name via the injected CacheTypeResolver.
    @SuppressWarnings("unchecked")
    private <T> Class<T> getValueType() {
        return (Class<T>) cacheTypeResolver.resolveClass(getName());
    }

    // Reflects on the loader's #call() return type since Callable<T> erases T at runtime;
    // falls back to Object if reflection fails for any reason (e.g. a lambda/synthetic class).
    @SuppressWarnings("unchecked")
    private <T> Class<T> detectValueType(Callable<T> valueLoader) {
        try {
            Method method = valueLoader.getClass().getDeclaredMethod("call");
            return (Class<T>) method.getReturnType();
        } catch (NoSuchMethodException e) {
            return (Class<T>) Object.class;
        }
    }
}
