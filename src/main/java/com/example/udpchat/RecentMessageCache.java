// RecentMessageCache.java
package com.example.udpchat;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RecentMessageCache {
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private final int maxSize;
    private final long ttlMs;

    public RecentMessageCache(int maxSize, long ttlMs) {
        this.maxSize = Math.max(256, maxSize);
        this.ttlMs = Math.max(1000, ttlMs);
    }

    public boolean isDuplicateAndRecord(String id) {
        if (id == null || id.isBlank()) return false;
        long now = System.currentTimeMillis();
        Long prev = seen.putIfAbsent(id, now + ttlMs);
        if (prev != null) {
            // Message ID is already in the cache. It's a duplicate.
            return true;
        }
        pruneIfNeeded();
        return false;
    }

    private void pruneIfNeeded() {
        if (seen.size() <= maxSize) return;
        long now = System.currentTimeMillis();
        seen.values().removeIf(expiryTime -> expiryTime < now);
    }
}