package com.bdmajora.coartatio.collections;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

// An immutable, insertion-ordered Map held as two parallel arrays
// Built for MultipartBakedModel.selectors, which vanilla creates as a LinkedHashMap and from then on only ever
// iterates. A LinkedHashMap entry is a 40-byte object carrying a hash, a next pointer and two order pointers;
// here an entry costs one array slot in each of two arrays. There is one multipart model per multipart
// blockstate, and packs built on them (pipes, cables, fences, wires) create thousands
// Lookups are a linear scan, which is the right shape at this size: selector counts are single digits, and a
// scan over two small arrays beats a hash probe plus a pointer chase. It is NOT a general-purpose map and is
// not exposed beyond the callers in this package
public class ArrayBackedLinkedMap<K, V> extends AbstractMap<K, V> {
    // Insertion order is the array order, which is what makes the iteration order match a LinkedHashMap's
    private final K[] keys;
    private final V[] values;

    // Copies out of the source map once; the source is not retained, so whatever it was can be collected
    // Arrays are exactly sized, so there is no growth slack to pay for
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
        return indexOf(key) >= 0;
    }

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

    // Immutable: the mutators throw instead of no-opping, so a mod trying to edit a baked model after the fact
    // gets a stack trace at bake time rather than a rendering bug much later
    @Override
    public V put(K key, V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return new EntrySet();
    }

    private final class EntrySet extends AbstractSet<Map.Entry<K, V>> {
        @Override
        public Iterator<Map.Entry<K, V>> iterator() {
            return new Iterator<Map.Entry<K, V>>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return this.index < ArrayBackedLinkedMap.this.keys.length;
                }

                // A fresh SimpleImmutableEntry per step, unlike Hydrogen's version, which recycles one mutable
                // entry. Recycling breaks any caller that collects the entry set, and on 1.12.2 — Forge's model
                // pipeline plus a long tail of mods wrapping baked models — that risk is not worth the one eden
                // allocation it would save
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

        @Override
        public int size() {
            return ArrayBackedLinkedMap.this.keys.length;
        }
    }
}
