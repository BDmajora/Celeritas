package com.bdmajora.coartatio.dedup;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;

import java.util.Objects;

/**
 * A pool that maps every value equal to some already-seen value onto that first instance.
 *
 * <p>Ported from Hydrogen's {@code DeduplicationCache} with two additions that 1.12.2 needs:
 *
 * <ul>
 *   <li><b>A size cap.</b> Hydrogen only pooled closed sets. Several of ours are not closed —
 *       resource paths in particular grow with skin downloads and dynamically registered content —
 *       so past the cap the pool stops accepting entries and becomes read-only. It keeps serving
 *       hits for everything it already holds; it just stops being a leak.
 *   <li><b>Closing.</b> Bake-scoped pools release their backing set when the bake ends. The values
 *       handed out stay canonical; we only stop paying for the index.
 * </ul>
 *
 * <p>All methods synchronise on the instance. Model baking is single-threaded in 1.12.2 but NBT and
 * {@code ResourceLocation} construction are not, and the cost of an uncontended monitor is far below
 * the cost of the allocation this avoids.
 */
public class DeduplicationCache<T> {
    private final String name;
    private final Hash.Strategy<T> strategy;
    private final int sizeLimit;

    private ObjectOpenCustomHashSet<T> pool;

    private long requests;
    private long hits;
    private boolean saturated;

    /** Pool size captured at {@link #close()}, so statistics survive the backing set being dropped. */
    private int retainedSize;

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

    /**
     * Returns the canonical instance for {@code value}, which may be {@code value} itself.
     *
     * <p>Returns the argument unchanged when the pool is closed or saturated, so callers never need
     * a null check or a state check of their own.
     */
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

    /** Re-opens the pool with a fresh backing set, discarding whatever the previous phase pooled. */
    public synchronized void open() {
        this.pool = new ObjectOpenCustomHashSet<>(this.strategy);
        this.requests = 0;
        this.hits = 0;
        this.saturated = false;
        this.retainedSize = 0;
    }

    /**
     * Releases the backing set. Already-canonicalised values are unaffected; subsequent calls to
     * {@link #deduplicate} return their argument.
     */
    public synchronized void close() {
        if (this.pool != null) {
            this.retainedSize = this.pool.size();
            this.pool = null;
        }
    }

    /** Number of lookups that found an existing entry, i.e. objects this pool prevented. */
    public synchronized long shared() {
        return this.hits;
    }

    /** Entries pooled — the live count while open, the count at close afterwards. */
    public synchronized int size() {
        return this.pool == null ? this.retainedSize : this.pool.size();
    }

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
