package com.bdmajora.impetus.engine.impl.util.iterator;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class ConcatenatedIterator<T> implements Iterator<T> {
    private final Iterator<Iterator<T>> iterators;

    private Iterator<T> currentIterator;

    public ConcatenatedIterator(Iterator<Iterator<T>> iterators) {
        this.iterators = iterators;
    }

    // Advances past exhausted inner iterators
    @Override
    public boolean hasNext() {
        if ((currentIterator == null || !currentIterator.hasNext()) && iterators.hasNext()) {
            currentIterator = iterators.next();
        }
        return currentIterator != null && currentIterator.hasNext();
    }

    // From the current inner iterator
    @Override
    public T next() {
        // hasNext manages replacing the iterator
        if (!hasNext()) {
            throw new NoSuchElementException();
        } else {
            return currentIterator.next();
        }
    }
}
