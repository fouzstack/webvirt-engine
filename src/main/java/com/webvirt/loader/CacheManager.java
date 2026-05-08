package com.webvirt.loader;

import android.util.LruCache;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CacheManager v3.1.1 — Production Hardened
 * 
 * CORREGIDO (auditoría C2, C3):
 * - NPE protection en CacheEntry.toResponse()
 * - AtomicLong para currentSizeBytes (thread-safe)
 */
class CacheManager {

    private final LruCache<String, CacheEntry> cache;
    final long maxSizeBytes;
    
    // C3: AtomicLong para operaciones thread-safe
    private final AtomicLong currentSizeBytes = new AtomicLong(0);

    CacheManager(long maxSizeBytes, int maxEntries) {
        this.maxSizeBytes = maxSizeBytes;
        
        int capacity = maxEntries > 0 ? maxEntries : (int) (maxSizeBytes / 1024);
        
        this.cache = new LruCache<String, CacheEntry>(capacity) {
            @Override
            protected int sizeOf(String key, CacheEntry entry) {
                return 1;
            }

            @Override
            protected void entryRemoved(boolean evicted, String key,
                                       CacheEntry oldValue, CacheEntry newValue) {
                if (oldValue != null && oldValue.data != null) {
                    // C3: AtomicLong.addAndGet
                    currentSizeBytes.addAndGet(-oldValue.data.length);
                    oldValue.data = null; // Liberar para GC
                }
            }
        };
    }

    /**
     * Obtiene respuesta cacheada con protección NPE (C2)
     */
    WebResourceResponse get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            WebResourceResponse response = entry.toResponse();
            if (response == null) {
                // Entrada eviccionada con data = null
                cache.remove(key);
                return null;
            }
            return response;
        }
        if (entry != null) {
            cache.remove(key);
        }
        return null;
    }

    CacheEntry getEntry(String key) {
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry;
        }
        if (entry != null) {
            cache.remove(key);
        }
        return null;
    }

    void put(String key, byte[] data, String mimeType, String encoding, 
             String etag, long lastModified) {
        if (data == null || data.length == 0) return;
        
        if (data.length > maxSizeBytes / 10) {
            return;
        }

        // C3: AtomicLong.addAndGet
        if (currentSizeBytes.addAndGet(data.length) > maxSizeBytes) {
            trimToFit(data.length);
        }

        cache.put(key, new CacheEntry(data, mimeType, encoding, etag, lastModified));
    }

    void remove(String key) {
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.data != null) {
            // C3: AtomicLong.addAndGet
            currentSizeBytes.addAndGet(-entry.data.length);
        }
        cache.remove(key);
    }

    void clear() {
        // C3: AtomicLong.set
        currentSizeBytes.set(0);
        cache.evictAll();
    }

    void trimToSize(long maxSize) {
        int targetEntries = (int) (maxSize / 1024);
        cache.trimToSize(Math.max(1, targetEntries));
    }

    private void trimToFit(int neededBytes) {
        int entriesToRemove = (int) Math.ceil((double) neededBytes / 1024) + 10;
        int targetSize = Math.max(1, cache.size() - entriesToRemove);
        cache.trimToSize(targetSize);
    }

    long getCurrentSizeBytes() {
        return currentSizeBytes.get();
    }

    int getEntryCount() {
        return cache.size();
    }

    /**
     * Entrada de caché con metadatos completos
     */
    static class CacheEntry {
        byte[] data;
        final String mimeType;
        final String encoding;
        final String etag;
        final long lastModified;
        final long createdAt;
        
        private static final long TTL_MILLIS = 60 * 60 * 1000; // 1 hora

        CacheEntry(byte[] data, String mimeType, String encoding, 
                   String etag, long lastModified) {
            this.data = data;
            this.mimeType = mimeType != null ? mimeType : "application/octet-stream";
            this.encoding = encoding != null ? encoding : "UTF-8";
            this.etag = etag;
            this.lastModified = lastModified;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > TTL_MILLIS;
        }

        /**
         * C2: Protección NPE post-evicción
         */
        WebResourceResponse toResponse() {
            if (data == null) return null;
            
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("ETag", etag);
            headers.put("Cache-Control", "public, max-age=3600");
            headers.put("Content-Length", String.valueOf(data.length));
            headers.put("Content-Type", mimeType);
            
            return new WebResourceResponse(
                mimeType,
                encoding,
                200,
                "OK",
                headers,
                new ByteArrayInputStream(data)
            );
        }
    }
}