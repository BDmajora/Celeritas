package com.bdmajora.coartatio.collections;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

// Immutable insertion-ordered map over two parallel arrays, built for MultipartBakedModel.selectors
// Linear lookup beats a hash probe at single-digit sizes; not a general-purpose map
public class ArrayBackedLinkedMap<K, V> extends AbstractMap<K, V> {
    // Array order is insertion order, which is what makes iteration match a LinkedHashMap
    private final K[] keys;
    private final V[] values;

    // Copies out of the source once and drops it; arrays are exactly sized, so no growth slack
    @SuppressWarnings("unchecked")
    public ArrayBackedLinkedMap(Map<K, V> src) {
        int size = src.size();

        this.keys = (K[]) new Object[size];
        this.values = (V[]) new Object[size];

        int i = 0;
        for (Map.Entry<K, V> entry : src.entrySet()) {
            this.keys[i] = entry.getKey();
            this.values[i] = entry.getValue();
            i++;
        }
    }

    // Fixed at construction, so the array length is the size
    @Override
    public int size() {
        return this.keys.length;
    }

    // Only empty when constructed from an empty source
    @Override
    public boolean isEmpty() {
        return this.keys.length == 0;
    }

    // Presence is just a successful scan
    @Override
    public boolean containsKey(Object key) {
        return indexOf(key) >= 0;
    }

    // Null return is ambiguous with a stored null, matching Map's contract
    @Override
    public V get(Object key) {
        int index = indexOf(key);
        return index < 0 ? null : this.values[index];
    }

    // Objects.equals rather than a bare equals so a null key is handled like any other value
    private int indexOf(Object key) {
        K[] keys = this.keys;

        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], key)) {
                return i;
            }
        }

        return -1;
    }

    // Mutators throw rather than no-op, so a mod editing a baked model fails at bake time
    // with a stack trace instead of producing a rendering bug much later
    @Override
    public V put(K key, V value) {
        throw new UnsupportedOperationException();
    }

    // Immutable; see put
    @Override
    public V remove(Object key) {
        throw new UnsupportedOperationException();
    }

    // Immutable; see put
    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    // AbstractMap routes keySet, values, forEach and toString through this
    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return new EntrySet();
    }

    private final class EntrySet extends AbstractSet<Map.Entry<K, V>> {
        // Walks the arrays by index; no allocation beyond the entry handed out per step
        @Override
        public Iterator<Map.Entry<K, V>> iterator() {
            return new Iterator<Map.Entry<K, V>>() {
                private int index;

                // Bounds check against the backing array, which never resizes
                @Override
                public boolean hasNext() {
                    return this.index < ArrayBackedLinkedMap.this.keys.length;
                }

                // A fresh entry per step, unlike Hydrogen which recycles one mutable entry
                // Recycling breaks callers that collect the entry set, and Forge's model pipeline
                // plus mods wrapping baked models make that risk cost more than the saved allocation
                @Override
                public Map.Entry<K, V> next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }

                    int i = this.index++;
                    return new SimpleImmutableEntry<>(ArrayBackedLinkedMap.this.keys[i],
                            ArrayBackedLinkedMap.this.values[i]);
                }
            };
        }

        // Mirrors the map's size; AbstractSet would otherwise count by iterating
        @Override
        public int size() {
            return ArrayBackedLinkedMap.this.keys.length;
        }
    }
}
