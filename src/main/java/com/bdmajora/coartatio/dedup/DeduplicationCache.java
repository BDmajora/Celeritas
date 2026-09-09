package com.bdmajora.coartatio.dedup;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;

import java.util.Objects;

// An interning pool: every value equal to something already seen is mapped back onto that first instance
// Ported from Hydrogen's DeduplicationCache with two additions 1.12.2 needs
// A size cap, because Hydrogen only pooled closed sets and several of ours are not closed — resource paths in
// particular keep growing with skin downloads and dynamically registered content. Past the cap the pool stops
// accepting new entries but keeps serving hits for everything it already holds, so it stops being a leak
// without becoming useless
// Closing, so bake-scoped pools can drop their backing set once the bake ends. Values already handed out stay
// canonical; only the index is given up
// Every method synchronises on the instance. Model baking is single-threaded on 1.12.2, but NBT and
// ResourceLocation construction are not, and an uncontended monitor costs far less than the allocation avoided
public class DeduplicationCache<T> {
    // Only used for toString, i.e. the memory report line
    private final String name;
    // How values are hashed and compared; the default wraps Objects, but the quad pool passes an array strategy
    // so int[] contents are compared rather than references
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
            @Override
            public int hashCode(T o) {
                return Objects.hashCode(o);
            }

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

    // Returns the canonical instance for value, which may be value itself if it is the first of its kind
    // Hands the argument straight back when it is null or the pool is closed, so no caller needs a null check or
    // a state check of its own
    public synchronized T deduplicate(T value) {
        if (value == null || this.pool == null) {
            return value;
        }

        this.requests++;

        // Measured by whether the pool grew, not by whether the returned instance differs from the
        // argument. Callers frequently hand back an instance the pool already issued — copying an
        // NBT compound re-inserts its already-interned keys — and those are shares too. Comparing
        // instances scores them as misses and produces nonsense like "0 shared, 115 unique" from
        // five thousand lookups.
        int before = this.pool.size();
        T existing = this.pool.addOrGet(value);

        if (this.pool.size() == before) {
            this.hits++;
            return existing;
        }

        // The pool grew. Enforce the cap here rather than before the lookup so that a saturated pool
        // still serves hits for everything it already knows about.
        if (this.pool.size() >= this.sizeLimit) {
            this.saturated = true;
        }

        return existing;
    }

    // Re-opens the pool with a fresh backing set, throwing away whatever the previous phase pooled
    // Counters are reset too, so the memory report describes this bake rather than every bake since launch
    public synchronized void open() {
        this.pool = new ObjectOpenCustomHashSet<>(this.strategy);
        this.requests = 0;
        this.hits = 0;
        this.saturated = false;
        this.retainedSize = 0;
    }

    // Drops the backing set. Values already canonicalised are unaffected — they are still shared with each
    // other — and later deduplicate calls simply return their argument
    // The size is copied out first so the memory report can still describe what this pool did
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

    // The memory report line. "unused" is distinguished from "0 shared" on purpose: a pool nothing ever asked
    // about usually means the injection that should feed it never fired
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
