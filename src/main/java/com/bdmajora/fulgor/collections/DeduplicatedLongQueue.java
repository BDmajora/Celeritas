package com.bdmajora.fulgor.collections;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

// FIFO of encoded positions that refuses to hold the same one twice; merges Phosphor's pooled segments
// with Alfheim's dedup so a bulk edit scheduling one position from every neighbour costs one evaluation
// The dedup set must be empty before a cycle's enqueues begin or a position updated twice in a row is dropped
// Not thread-safe except isEmpty, which reads a volatile so the engine can check without the lock
public final class DeduplicatedLongQueue {
    private static final int SEGMENT_SIZE = 1 << 10;

    // Entry count past which the dedup set is replaced rather than cleared, so a worldgen burst that grows
    // it to millions of entries doesn't keep that array alive for the rest of the session (~512 KiB table).
    private static final int RETAINED_SET_CAPACITY = 1 << 15;

    private final Pool pool;
    private final int initialSetCapacity;

    // Kill switch, not a tuning knob (dedup is strictly a win) — lets a lighting bug be bisected without a rebuild
    private final boolean deduplicate;

    private LongOpenHashSet seen;

    private Segment head;
    private Segment tail;

    // Read cursor into head
    private int headIndex;

    private int size;

    // Volatile so isEmpty() is safe to call from a thread that doesn't hold the engine's lock
    private volatile boolean empty = true;

    public DeduplicatedLongQueue(Pool pool, int initialCapacity, boolean deduplicate) {
        this.pool = pool;
        this.initialSetCapacity = initialCapacity;
        this.deduplicate = deduplicate;
        this.seen = deduplicate ? new LongOpenHashSet(initialCapacity) : null;
    }

    // Appends a value unless it is already pending; returns whether it was appended
    public boolean enqueue(long value) {
        if (this.deduplicate && !this.seen.add(value)) {
            return false;
        }

        if (this.tail == null) {
            this.head = this.tail = this.pool.acquire();
            this.headIndex = 0;
            this.empty = false;
        } else if (this.tail.writeIndex == SEGMENT_SIZE) {
            Segment segment = this.pool.acquire();
            this.tail.next = segment;
            this.tail = segment;
        }

        this.tail.values[this.tail.writeIndex++] = value;
        this.size++;

        return true;
    }

    // Removes and returns the oldest value; undefined if the queue is empty
    public long dequeue() {
        long value = this.head.values[this.headIndex++];

        if (this.headIndex == this.head.writeIndex) {
            Segment next = this.head.next;

            this.pool.release(this.head);

            this.head = next;
            this.headIndex = 0;

            if (next == null) {
                this.tail = null;
                this.empty = true;
            }
        }

        this.size--;

        return value;
    }

    // Volatile read, safe to call off-thread
    public boolean isEmpty() {
        return this.empty;
    }

    // Element count; only meaningful under the lock
    public int size() {
        return this.size;
    }

    // Forgets which values have been seen, so a position can be scheduled again; call so the set is
    // empty when the next cycle's enqueues start (see class comment for placement per queue)
    public void resetDeduplication() {
        if (!this.deduplicate) {
            return;
        }

        if (this.seen.size() > RETAINED_SET_CAPACITY) {
            this.seen = new LongOpenHashSet(this.initialSetCapacity);
        } else {
            this.seen.clear();
        }
    }

    // Segment store shared by every queue belonging to one engine. Sharing matters: darkening and
    // brightening queues run in strict succession one light level at a time, so segments one level
    // frees are immediately reusable by the next, instead of each of the 34 queues holding its own high-water mark.
    public static final class Pool {
        // Ceiling on retained segments, not queue size (a queue can still allocate past this, it just
        // won't get the memory back). 1024 segments = 8 MiB, comfortably above any realistic high-water mark.
        private static final int MAX_CACHED_SEGMENTS = 1 << 10;

        private Segment free;
        private int freeCount;

        Segment acquire() {
            Segment segment = this.free;

            if (segment == null) {
                return new Segment();
            }

            this.free = segment.next;
            this.freeCount--;

            segment.next = null;
            segment.writeIndex = 0;

            return segment;
        }

        void release(Segment segment) {
            if (this.freeCount >= MAX_CACHED_SEGMENTS) {
                return;
            }

            segment.next = this.free;
            segment.writeIndex = 0;

            this.free = segment;
            this.freeCount++;
        }
    }

    private static final class Segment {
        private final long[] values = new long[SEGMENT_SIZE];
        private int writeIndex;
        private Segment next;
    }
}
