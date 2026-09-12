package com.bdmajora.impetus.engine.impl.util.iterator;

import java.util.NoSuchElementException;

public class ByteArrayIterator implements ByteIterator {
    private final byte[] elements;
    private final int lastIndex;

    private int index;

    public ByteArrayIterator(byte[] elements, int lastIndex) {
        this.elements = elements;
        this.lastIndex = lastIndex;
        this.index = 0;
    }

    // Below the end index
    @Override
    public boolean hasNext() {
        return this.index < this.lastIndex;
    }

    // Next byte widened without sign extension
    @Override
    public int nextByteAsInt() {
        if (!this.hasNext()) {
            throw new NoSuchElementException();
        }

        return Byte.toUnsignedInt(this.elements[this.index++]);
    }
}
