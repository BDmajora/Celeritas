package com.bdmajora.coartatio.dedup;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/**
 * A string pool striped across independently locked shards.
 *
 * <p>{@link DeduplicationCache} takes one lock per call, which is right for the model pools — those
 * are filled by a single-threaded bake. NBT keys are not: they are interned from the netty worker
 * that decodes packets, from the chunk IO thread, and from the client thread, several thousand times
 * a second while chunks stream in. One global monitor there would turn a memory win into a
 * throughput loss, which is not a trade worth making.
 *
 * <p>Striping by hash means threads working on different keys almost never contend, and the shard
 * for a given key is fixed, so a key still resolves to exactly one canonical instance.
 *
 * <p>Sixteen shards is enough to make contention negligible at 1.12.2's thread counts without the
 * per-shard hash tables costing more than the strings they save.
 */
public final class ShardedStringCache {
    private static final int SHARD_COUNT = 16;
    private static final int SHARD_MASK = SHARD_COUNT - 1;

    private final String name;
    private final int shardSizeLimit;
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
