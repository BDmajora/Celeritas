package com.bdmajora.coartatio.collections;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, insertion-ordered {@link Map} stored as two parallel arrays.
 *
 * <p>Built for {@code MultipartBakedModel.selectors}, which vanilla creates as a
 * {@code LinkedHashMap} and thereafter only iterates. A {@code LinkedHashMap} entry is a 40-byte
 * object with a hash, a next pointer and two order pointers; here an entry costs eight bytes of
 * array slot. Multipart models are one per multipart blockstate, and packs that lean on them
 * (pipes, cables, fences, wires) create thousands.
 *
 * <p>Lookups are a linear scan. That is the right shape here: selector counts are single digits, and
 * a scan over two small arrays beats a hash probe plus a pointer chase at that size. The class is
 * not suitable as a general-purpose map and is not exposed outside this package's callers.
 *
 * <p>Unlike Hydrogen's equivalent, the entry set iterator allocates a fresh entry per step instead
 * of recycling one mutable instance. Recycling breaks any caller that collects the entry set — and
 * on 1.12.2, with Forge's model pipeline and a long tail of mods wrapping baked models, that risk
 * is not worth the eden allocation it saves.
 */
public class ArrayBackedLinkedMap<K, V> extends AbstractMap<K, V> {
    private final K[] keys;
    private final V[] values;

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

    private int indexOf(Object key) {
        K[] keys = this.keys;

        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], key)) {
                return i;
            }
        }

        return -1;
    }

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
