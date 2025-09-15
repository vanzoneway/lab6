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
        if (prev != null && prev >= now) return true;
        pruneIfNeeded(now);
        return false;
    }

    private void pruneIfNeeded(long now) {
        if (seen.size() <= maxSize) return;
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() < now) it.remove();
        }
        if (seen.size() > maxSize) {
            int target = (int) (maxSize * 0.7);
            int cnt = 0;
            for (String k : seen.keySet()) {
                if (cnt++ >= seen.size() - target) break;
                seen.remove(k);
            }
        }
    }
}
