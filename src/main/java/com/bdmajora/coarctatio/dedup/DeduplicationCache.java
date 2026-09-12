package com.bdmajora.coarctatio.dedup;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;

import java.util.Objects;

// Interning pool mapping equal values onto the first instance seen (from Hydrogen, plus a size cap and closing for bake-scoped pools); synchronised since an uncontended monitor is cheaper than the allocation avoided
public class DeduplicationCache<T> {
    // Only used for toString, i.e. the memory report line
    private final String name;
    // How values are hashed and compared; the quad pool passes an array strategy so int[] contents are compared rather than references
    private final Hash.Strategy<T> strategy;
    private final int sizeLimit;

    // Null once closed, which is also how deduplicate knows to stop pooling
    private ObjectOpenCustomHashSet<T> pool;

    // Counters for the memory report; requests includes hits
    private long requests;
    private long hits;
    private boolean saturated;

    // Pool size captured at close(), so the statistics survive the backing set being dropped
    private int retainedSize;

    // Default strategy: ordinary hashCode/equals, but null-tolerant via Objects
    public DeduplicationCache(String name, int sizeLimit) {
        this(name, sizeLimit, new Hash.Strategy<T>() {
            // Default strategy: the value's own hashCode
            @Override
            public int hashCode(T o) {
                return Objects.hashCode(o);
            }

            // Default strategy: the value's own equals
            @Override
            public boolean equals(T a, T b) {
                return Objects.equals(a, b);
            }
        });
    }

    public DeduplicationCache(String name, int sizeLimit, Hash.Strategy<T> strategy) {
        this.name = name;
        this.sizeLimit = sizeLimit;
        this.strategy = strategy;
        this.pool = new ObjectOpenCustomHashSet<>(strategy);
    }

    // Returns the canonical instance for value (possibly value itself); null and a closed pool hand the argument straight back so callers need no checks
    public synchronized T deduplicate(T value) {
        if (value == null || this.pool == null) {
            return value;
        }

        this.requests++;

        // Scored by whether the pool grew, not by instance identity: callers often hand back an instance the pool issued (copying an NBT compound re-inserts interned keys), and those are shares too
        int before = this.pool.size();
        T existing = this.pool.addOrGet(value);

        if (this.pool.size() == before) {
            this.hits++;
            return existing;
        }

        // Enforce the cap after the lookup so a saturated pool still serves hits for everything it knows
        if (this.pool.size() >= this.sizeLimit) {
            this.saturated = true;
        }

        return existing;
    }

    // Re-opens with a fresh backing set and reset counters, so the memory report describes this bake rather than every bake since launch
    public synchronized void open() {
        this.pool = new ObjectOpenCustomHashSet<>(this.strategy);
        this.requests = 0;
        this.hits = 0;
        this.saturated = false;
        this.retainedSize = 0;
    }

    // Drops the backing set; canonicalised values stay shared, later deduplicate calls return their argument, and the size is copied first for the memory report
    public synchronized void close() {
        if (this.pool != null) {
            this.retainedSize = this.pool.size();
            this.pool = null;
        }
    }

    // Lookups that found an existing entry, i.e. the number of objects this pool kept from being allocated
    public synchronized long shared() {
        return this.hits;
    }

    // Entries pooled: the live count while open, the count captured at close afterwards
    public synchronized int size() {
        return this.pool == null ? this.retainedSize : this.pool.size();
    }

    // The memory report line; "unused" is distinct from "0 shared" because a pool nothing asked about usually means its injection never fired
    @Override
    public synchronized String toString() {
        if (this.requests == 0) {
            return this.name + " (unused)";
        }

        return String.format("%s (%d/%d shared, %d unique%s%s)",
                this.name, this.hits, this.requests, size(),
                this.saturated ? ", saturated" : "",
                this.pool == null ? ", closed" : "");
    }
}
