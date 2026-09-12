package com.bdmajora.coarctatio.dedup;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

// String pool striped across independently locked shards for NBT keys interned concurrently from netty, chunk IO and client threads; a key's shard is fixed by hash so it resolves to one instance
public final class ShardedStringCache {
    // Sixteen makes contention negligible at 1.12.2's thread counts without the per-shard tables outweighing the strings saved; power of two so the index is a mask
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

    // Returns the canonical instance, spreading the hash first since short similar keys cluster on low bits
    public String deduplicate(String value) {
        if (value == null) {
            return null;
        }

        // Spread the low bits: String.hashCode of short similar keys ("x", "y", "z") clusters and the shard is selected by the low bits
        int hash = value.hashCode();
        hash ^= hash >>> 16;

        ObjectOpenHashSet<String> shard = this.shards[hash & SHARD_MASK];

        synchronized (shard) {
            if (shard.size() >= this.shardSizeLimit) {
                // Saturated: still serve hits, just stop growing.
                String existing = shard.get(value);
                return existing == null ? value : existing;
            }

            // Scored by whether the shard grew, not instance identity (see DeduplicationCache.deduplicate); copying an NBT compound re-inserts keys the pool issued, so naive scoring calls nearly every share a miss
            int before = shard.size();
            String existing = shard.addOrGet(value);

            this.requests++;
            if (shard.size() == before) {
                this.hits++;
            }

            return existing;
        }
    }

    // Empties every shard (issued strings stay valid, just unshared); trim() after clear() is what actually releases memory since fastutil's clear keeps the grown table
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

    // Lookups that found an existing entry, i.e. strings this pool kept from being allocated; read without a lock since a stale report figure is fine
    public long shared() {
        return this.hits;
    }

    // Sum over shards, each under its own lock
    public int size() {
        int total = 0;

        for (ObjectOpenHashSet<String> shard : this.shards) {
            synchronized (shard) {
                total += shard.size();
            }
        }

        return total;
    }

    // Hit-rate summary; counters are read without a lock since a torn read only costs a slightly wrong log line
    @Override
    public String toString() {
        // requests/hits are written under per-shard locks and read without one; a torn read only costs a slightly wrong log line
        long requests = this.requests;

        if (requests == 0) {
            return this.name + " (unused)";
        }

        return String.format("%s (%d/%d shared, %d unique across %d shards)",
                this.name, this.hits, requests, size(), SHARD_COUNT);
    }
}
