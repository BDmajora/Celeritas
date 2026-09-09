package com.bdmajora.coartatio.dedup;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

// A string pool striped across independently locked shards
// DeduplicationCache takes one lock per call, which is right for the model pools because a bake is
// single-threaded. NBT keys are not: they are interned from the netty worker decoding packets, from the chunk
// IO thread and from the client thread, several thousand times a second while chunks stream in, and one global
// monitor there turns a memory win into a throughput loss
// Striping by hash means threads working on different keys almost never contend, and because a key's shard is
// fixed it still resolves to exactly one canonical instance
public final class ShardedStringCache {
    // Sixteen is enough to make contention negligible at 1.12.2's thread counts without the per-shard hash
    // tables costing more than the strings they save
    // A power of two so the shard index is a mask rather than a modulo
    private static final int SHARD_COUNT = 16;
    private static final int SHARD_MASK = SHARD_COUNT - 1;

    private final String name;
    // The overall cap divided evenly; at least 1 so a tiny configured limit cannot produce a zero-capacity shard
    private final int shardSizeLimit;
    // Each shard is its own monitor, which is why they are locked individually below rather than as a group
    private final ObjectOpenHashSet<String>[] shards;

    private long requests;
    private long hits;

    @SuppressWarnings("unchecked")
    public ShardedStringCache(String name, int sizeLimit) {
        this.name = name;
        this.shardSizeLimit = Math.max(1, sizeLimit / SHARD_COUNT);
        this.shards = new ObjectOpenHashSet[SHARD_COUNT];

        for (int i = 0; i < SHARD_COUNT; i++) {
            this.shards[i] = new ObjectOpenHashSet<>();
        }
    }

    public String deduplicate(String value) {
        if (value == null) {
            return null;
        }

        // Spread the low bits: String.hashCode of short, similar keys ("x", "y", "z") clusters, and
        // we are selecting a shard with the low bits.
        int hash = value.hashCode();
        hash ^= hash >>> 16;

        ObjectOpenHashSet<String> shard = this.shards[hash & SHARD_MASK];

        synchronized (shard) {
            if (shard.size() >= this.shardSizeLimit) {
                // Saturated: still serve hits, just stop growing.
                String existing = shard.get(value);
                return existing == null ? value : existing;
            }

            // Scored by whether the shard grew, not by instance identity — see the same reasoning in
            // DeduplicationCache.deduplicate. NBT is the worst case for the naive version: copying a
            // compound re-inserts keys the pool itself issued, so almost every genuine share looks
            // like a miss.
            int before = shard.size();
            String existing = shard.addOrGet(value);

            this.requests++;
            if (shard.size() == before) {
                this.hits++;
            }

            return existing;
        }
    }

    // Empties every shard. Strings already issued stay valid, they just stop being shared with new arrivals
    // trim() after clear() is what actually gives the memory back: fastutil's clear leaves the grown table in
    // place, so without it a spike in unique keys is never released
    public void clear() {
        for (ObjectOpenHashSet<String> shard : this.shards) {
            synchronized (shard) {
                shard.clear();
                shard.trim();
            }
        }

        this.requests = 0;
        this.hits = 0;
    }

    // Lookups that found an existing entry, i.e. the number of strings this pool kept from being allocated
    // Read without a lock: it is a report figure, and a slightly stale count is not worth serialising the pool
    public long shared() {
        return this.hits;
    }

    public int size() {
        int total = 0;

        for (ObjectOpenHashSet<String> shard : this.shards) {
            synchronized (shard) {
                total += shard.size();
            }
        }

        return total;
    }

    @Override
    public String toString() {
        // requests/hits are written under per-shard locks and read without one; they are statistics,
        // and a torn read costs nothing but a slightly wrong log line.
        long requests = this.requests;

        if (requests == 0) {
            return this.name + " (unused)";
        }

        return String.format("%s (%d/%d shared, %d unique across %d shards)",
                this.name, this.hits, requests, size(), SHARD_COUNT);
    }
}
