package com.bdmajora.impetus.engine.impl.render.mesh.util;

import it.unimi.dsi.fastutil.longs.LongBidirectionalIterator;
import it.unimi.dsi.fastutil.longs.LongRBTreeSet;

// First-fit free-list allocator with coalescing and in-place growth, used for quads and staging bytes
// (address, size) packs into one long kept in two sorted sets with the fields swapped: FREE by size for
// first-fit, TAKEN by address so coalescing is a look at the two neighbours
public class SegmentedAllocator {
    // Returned by alloc() when the request cannot be satisfied within the limit; -1 rather than an exception
    // because running out is a normal, recoverable condition for the geometry arena
    public static final long OUT_OF_SPACE = -1L;

    // 34 address bits and 30 size bits: a single allocation caps at 2^30 units, the space at 2^34
    private static final int ADDR_BITS = 34;
    private static final int SIZE_BITS = 64 - ADDR_BITS;
    private static final long SIZE_MASK = (1L << SIZE_BITS) - 1;
    private static final long ADDR_MASK = (1L << ADDR_BITS) - 1;

    private final LongRBTreeSet free = new LongRBTreeSet();
    private final LongRBTreeSet taken = new LongRBTreeSet();

    private long sizeLimit = Long.MAX_VALUE;
    private long totalSize;

    // Whether the last alloc/free/expand moved the high-water mark, so a caller backed by a real allocation knows
    // when it has to grow or can shrink
    private boolean resized;

    // Caps growth
    public void setLimit(long limit) {
        this.sizeLimit = limit;
    }

    // The high-water mark, not the sum of live allocations
    public long getTotalSize() {
        return this.totalSize;
    }

    // Whether the last alloc grew the backing store, so callers re-fetch addresses
    public boolean didResize() {
        return this.resized;
    }

    // First-fit from the free list, growing when nothing fits
    public long alloc(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Allocation size must be positive");
        }

        // -1 because the iterator is exclusive and blocks of exactly this size must still be found
        LongBidirectionalIterator iterator = this.free.iterator(((long) size << ADDR_BITS) - 1);

        if (!iterator.hasNext()) {
            // Nothing free is big enough, so grow past the high-water mark
            this.resized = true;

            if (this.totalSize + size > this.sizeLimit) {
                return OUT_OF_SPACE;
            }

            long address = this.totalSize;
            this.totalSize += size;
            this.taken.add((address << SIZE_BITS) | size);
            return address;
        }

        long slot = iterator.nextLong();
        iterator.remove();

        long slotSize = slot >>> ADDR_BITS;
        long slotAddress = slot & ADDR_MASK;

        if (slotSize == size) {
            this.taken.add((slotAddress << SIZE_BITS) | slotSize);
        } else {
            // Split: the front becomes the allocation, the remainder goes back on the free list
            this.taken.add((slotAddress << SIZE_BITS) | size);
            this.free.add(((slotSize - size) << ADDR_BITS) | (slotAddress + size));
        }

        this.resized = false;
        return slotAddress;
    }

    // Releases an allocation and merges it with any free space immediately before or after it
    // Returns the size that was freed, which is how callers recover an allocation's length without tracking it
    public int free(long address) {
        address &= ADDR_MASK;

        LongBidirectionalIterator iterator = this.taken.iterator(address << SIZE_BITS);
        long slot = iterator.nextLong();

        if (slot >>> SIZE_BITS != address) {
            throw new IllegalStateException("Freeing an address that was never allocated: " + address);
        }

        long size = slot & SIZE_MASK;
        iterator.remove();

        // A previous allocation exists, so the gap between where it ends and where this one starts is either
        // nothing or exactly one free block that must be absorbed
        if (iterator.hasPrevious()) {
            long previous = iterator.previousLong();
            long previousEnd = (previous >>> SIZE_BITS) + (previous & SIZE_MASK);

            if (previousEnd != address) {
                long gap = address - previousEnd;
                this.free.remove((gap << ADDR_BITS) | previousEnd);
                slot = (previousEnd << SIZE_BITS) | ((slot & SIZE_MASK) + gap);
            }

            // Step the iterator back to where it was, so the hasNext() below looks at the right neighbour
            iterator.nextLong();
        } else if (!this.free.isEmpty()) {
            // No previous allocation means this one starts the space; anything free before it must be the block
            // that begins at address 0
            if (this.free.remove(address << ADDR_BITS)) {
                slot = address + size;
            }
        }

        if (iterator.hasNext()) {
            long next = iterator.nextLong();
            long end = (slot >>> SIZE_BITS) + (slot & SIZE_MASK);

            if (end != next >>> SIZE_BITS) {
                long gap = (next >>> SIZE_BITS) - end;
                this.free.remove((gap << ADDR_BITS) | end);
                slot = (slot & (ADDR_MASK << SIZE_BITS)) | ((slot & SIZE_MASK) + gap);
            }
        } else {
            // Nothing after it: this was the tail, so the space shrinks instead of gaining a free block
            this.resized = true;
            this.totalSize -= slot & SIZE_MASK;
            return (int) size;
        }

        this.resized = false;
        // Swap the fields round into FREE's (size, address) order
        this.free.add((slot >>> SIZE_BITS) | (slot << ADDR_BITS));
        return (int) size;
    }

    // Grows an existing allocation in place, which is what lets consecutive staging writes share one allocation
    // instead of one per upload; false when the following space is taken and the caller has to start a new one
    public boolean expand(long address, int extra) {
        address &= ADDR_MASK;

        LongBidirectionalIterator iterator = this.taken.iterator(address << SIZE_BITS);

        if (!iterator.hasNext()) {
            return false;
        }

        long slot = iterator.nextLong();

        if (slot >>> SIZE_BITS != address) {
            throw new IllegalStateException("Expanding an address that was never allocated: " + address);
        }

        long grownSlot = (slot & (ADDR_MASK << SIZE_BITS)) | ((slot & SIZE_MASK) + extra);
        this.resized = false;

        if (!iterator.hasNext()) {
            // At the tail, so growing just moves the high-water mark
            if (this.totalSize + extra > this.sizeLimit) {
                return false;
            }

            iterator.remove();
            this.taken.add(grownSlot);
            this.totalSize += extra;
            this.resized = true;
            return true;
        }

        long next = iterator.nextLong();
        long end = (slot >>> SIZE_BITS) + (slot & SIZE_MASK);
        long gap = (next >>> SIZE_BITS) - end;

        if (extra > gap) {
            return false;
        }

        this.free.remove((gap << ADDR_BITS) | end);
        // Two steps back, because reading `next` left the cursor past the entry being replaced
        iterator.previousLong();
        iterator.previousLong();
        iterator.remove();
        this.taken.add(grownSlot);

        if (extra != gap) {
            this.free.add(((gap - extra) << ADDR_BITS) | (end + extra));
        }

        return true;
    }

    // Size of a live allocation
    public long getSize(long address) {
        address &= ADDR_MASK;

        LongBidirectionalIterator iterator = this.taken.iterator(address << SIZE_BITS);

        if (!iterator.hasNext()) {
            throw new IllegalArgumentException("No allocation at address " + address);
        }

        long slot = iterator.nextLong();

        if (slot >>> SIZE_BITS != address) {
            throw new IllegalStateException("No allocation at address " + address);
        }

        return slot & SIZE_MASK;
    }
}
