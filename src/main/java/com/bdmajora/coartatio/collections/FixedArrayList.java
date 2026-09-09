package com.bdmajora.coartatio.collections;

import com.google.common.collect.Iterators;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

// An immutable List that is exactly one object plus one exactly-sized array. Ported from Hydrogen
// The lists this replaces are ArrayLists built by Lists.newArrayList() and then never touched again, so they
// carry a growth buffer that is on average a third wasted, on top of the ArrayList object itself. Once per face
// per baked model, that adds up
// Implements List directly rather than extending AbstractList, so nothing inherits a modCount field or the
// AbstractList iterator machinery
public class FixedArrayList<T> implements List<T> {
    private final T[] array;

    @SuppressWarnings("unchecked")
    public FixedArrayList(List<T> list) {
        this(list.toArray((T[]) new Object[0]));
    }

    public FixedArrayList(T[] array) {
        this.array = array;
    }

    @Override
    public int size() {
        return this.array.length;
    }

    @Override
    public boolean isEmpty() {
        return this.array.length == 0;
    }

    @Override
    public boolean contains(Object o) {
        return ArrayUtils.contains(this.array, o);
    }

    @Override
    public Iterator<T> iterator() {
        return Iterators.forArray(this.array);
    }

    // Cloned, not returned directly: the List contract says the caller owns the result, and handing out the
    // backing array would let it be written through
    @Override
    public Object[] toArray() {
        return this.array.clone();
    }

    // The awkward Collection.toArray(T[]) contract: allocate a new array of the caller's component type when
    // theirs is too small, otherwise fill theirs and null-terminate the leftover so they can find the end
    @SuppressWarnings("unchecked")
    @Override
    public <T1> T1[] toArray(T1[] dst) {
        T[] src = this.array;

        if (dst.length < src.length) {
            return (T1[]) Arrays.copyOf(src, src.length, dst.getClass());
        }

        System.arraycopy(src, 0, dst, 0, src.length);

        if (dst.length > src.length) {
            dst[src.length] = null;
        }

        return dst;
    }

    // Every mutator throws rather than silently no-opping: if a mod does try to add a quad to a baked model
    // after the fact, that should be a loud stack trace at bake time, not a rendering bug hours later
    @Override
    public boolean add(T t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!ArrayUtils.contains(this.array, o)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public T get(int index) {
        return this.array[index];
    }

    @Override
    public T set(int index, T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(int index, T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public T remove(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(Object o) {
        return ArrayUtils.indexOf(this.array, o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return ArrayUtils.lastIndexOf(this.array, o);
    }

    // Delegated rather than thrown, because Forge's model pipeline and a long tail of mods do walk
    // quad lists this way. Wrapped as unmodifiable so a caller cannot reach through the iterator and
    // write to the array — which, once quad vertex data is pooled, would corrupt unrelated quads.
    @Override
    public ListIterator<T> listIterator() {
        return java.util.Collections.unmodifiableList(Arrays.asList(this.array)).listIterator();
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        return java.util.Collections.unmodifiableList(Arrays.asList(this.array)).listIterator(index);
    }

    @Override
    public List<T> subList(int fromIndex, int toIndex) {
        return java.util.Collections.unmodifiableList(Arrays.asList(this.array).subList(fromIndex, toIndex));
    }

    // List.equals contract: equal to any List with the same elements in the same order, whatever its class
    // Compared through the other list's iterator so it works against a LinkedList as well as an ArrayList
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof List)) {
            return false;
        }

        List<?> other = (List<?>) o;
        if (other.size() != this.array.length) {
            return false;
        }

        Iterator<?> it = other.iterator();
        for (T element : this.array) {
            if (!java.util.Objects.equals(element, it.next())) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        // Must match AbstractList.hashCode so that mixed-implementation comparisons behave.
        int hash = 1;
        for (T element : this.array) {
            hash = 31 * hash + (element == null ? 0 : element.hashCode());
        }
        return hash;
    }

    @Override
    public String toString() {
        return Arrays.toString(this.array);
    }
}
