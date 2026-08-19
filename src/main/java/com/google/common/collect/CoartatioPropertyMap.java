package com.google.common.collect;

import java.util.Map;

/**
 * A compact {@link ImmutableMap} for block state property maps.
 *
 * <p><b>This class lives in Guava's package on purpose.</b> {@code ImmutableMap}'s constructor is
 * package-private, so a subclass has to share Guava's runtime package — same package name and same
 * classloader. It is loaded through
 * {@code com.bdmajora.coartatio.util.ClassDefineTool}, and must therefore reference <b>only</b>
 * {@code com.google.common.collect} and {@code java.*}: if Guava turns out to be on a loader that
 * cannot see the Impetus jar, any other import becomes a {@code NoClassDefFoundError} at first use.
 *
 * <h2>What it saves</h2>
 *
 * <p>Guava's {@code RegularImmutableMap} stores an {@code ImmutableMapEntry} object per entry — key,
 * value, hash and a collision pointer — plus an entry array and a hash table roughly twice the entry
 * count. For a four-property block state that is around 240 bytes.
 *
 * <p>Here the keys array is <i>shared by every state of a block</i>, because every state of a block
 * has exactly the same properties in the same order and differs only in the values. So a state costs
 * this object plus a values array: about 72 bytes, with the keys paid for once per block instead of
 * once per state.
 *
 * <p>Lookups are a linear scan. Blocks have single-digit property counts, and a scan of one small
 * array beats a hash probe plus a pointer chase at that size.
 *
 * <h2>Why the overrides matter</h2>
 *
 * <p>{@code ImmutableMap.hashCode()} is {@code Sets.hashCodeImpl(entrySet())}, and {@code entrySet()}
 * lazily builds <i>and caches</i> an {@code ImmutableSet} of {@code Entry} objects. Since
 * {@code StateImplementation.hashCode()} delegates straight to this map, and block states live in
 * hash maps all over the game, inheriting that behaviour would materialise a full entry set per
 * state on first use and undo the entire saving. {@code hashCode}, {@code equals},
 * {@code containsKey} and {@code containsValue} are all answered from the arrays for that reason.
 */
public final class CoartatioPropertyMap<K, V> extends ImmutableMap<K, V> {
    private final Object[] keys;
    private final Object[] values;

    public CoartatioPropertyMap(Object[] keys, Object[] values) {
        this.keys = keys;
        this.values = values;
    }

    private int indexOf(Object key) {
        Object[] keys = this.keys;

        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == key || keys[i].equals(key)) {
                return i;
            }
        }

        return -1;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        if (key == null) {
            return null;
        }

        int index = indexOf(key);
        return index < 0 ? null : (V) this.values[index];
    }

    @Override
    public int size() {
        return this.keys.length;
    }

    @Override
    public boolean isEmpty() {
        return this.keys.length == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return key != null && indexOf(key) >= 0;
    }

    @Override
    public boolean containsValue(Object value) {
        if (value == null) {
            return false;
        }

        for (Object candidate : this.values) {
            if (candidate == value || value.equals(candidate)) {
                return true;
            }
        }

        return false;
    }

    @Override
    boolean isPartialView() {
        return false;
    }

    /**
     * Only reached if something genuinely iterates the map. {@code ImmutableMap} caches the result,
     * so a state that is iterated once pays for an entry set once — the same cost vanilla would
     * always have paid.
     */
    @Override
    @SuppressWarnings("unchecked")
    ImmutableSet<Map.Entry<K, V>> createEntrySet() {
        Map.Entry<K, V>[] entries = new Map.Entry[this.keys.length];

        for (int i = 0; i < this.keys.length; i++) {
            entries[i] = Maps.immutableEntry((K) this.keys[i], (V) this.values[i]);
        }

        return ImmutableSet.copyOf(entries);
    }

    /** {@code Map.hashCode} contract: the sum of the entries' hashes. Computed without allocating. */
    @Override
    public int hashCode() {
        int hash = 0;

        for (int i = 0; i < this.keys.length; i++) {
            Object value = this.values[i];
            hash += this.keys[i].hashCode() ^ (value == null ? 0 : value.hashCode());
        }

        return hash;
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) {
            return true;
        }
        if (!(object instanceof Map)) {
            return false;
        }

        Map<?, ?> other = (Map<?, ?>) object;

        if (other.size() != this.keys.length) {
            return false;
        }

        for (int i = 0; i < this.keys.length; i++) {
            Object mine = this.values[i];
            Object theirs = other.get(this.keys[i]);

            if (mine == null ? theirs != null || !other.containsKey(this.keys[i]) : !mine.equals(theirs)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public String toString() {
        if (this.keys.length == 0) {
            return "{}";
        }

        StringBuilder builder = new StringBuilder("{");

        for (int i = 0; i < this.keys.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(this.keys[i]).append('=').append(this.values[i]);
        }

        return builder.append('}').toString();
    }
}
