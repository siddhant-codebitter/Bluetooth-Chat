package com.example.bluetoothchat.mesh;

import androidx.annotation.NonNull;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers recently seen packet IDs so the same packet is never processed or
 * forwarded twice. Without this, flooding would make packets bounce between
 * phones forever.
 *
 * Entries expire after 'ttlMs' (so a message that is deliberately re-sent later
 * is accepted again) and the cache never holds more than 'maxEntries'.
 * Thread-safe.
 */
public class SeenPacketCache {

    public static final int DEFAULT_MAX_ENTRIES = 5000;
    public static final long DEFAULT_TTL_MS = 2 * 60 * 1000L;

    private final int maxEntries;
    private final long ttlMs;

    // Insertion order == time order, so expired entries are always at the front.
    private final LinkedHashMap<String, Long> entries = new LinkedHashMap<>();

    public SeenPacketCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_MS);
    }

    public SeenPacketCache(int maxEntries, long ttlMs) {
        this.maxEntries = maxEntries;
        this.ttlMs = ttlMs;
    }

    /**
     * Records the packet ID.
     *
     * @return true if it was NEW (process it), false if it was already seen (drop it).
     */
    public synchronized boolean markIfNew(@NonNull String packetId) {
        long now = System.currentTimeMillis();
        purgeExpired(now);
        if (entries.containsKey(packetId)) {
            return false;
        }
        entries.put(packetId, now);
        while (entries.size() > maxEntries) {
            Iterator<Map.Entry<String, Long>> it = entries.entrySet().iterator();
            it.next();
            it.remove();
        }
        return true;
    }

    public synchronized boolean contains(@NonNull String packetId) {
        purgeExpired(System.currentTimeMillis());
        return entries.containsKey(packetId);
    }

    public synchronized void clear() {
        entries.clear();
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<String, Long>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > ttlMs) {
                it.remove();
            } else {
                break; // everything after this one is newer
            }
        }
    }
}