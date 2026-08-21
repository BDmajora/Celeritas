package com.bdmajora.fulgor.collections;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The queue the lighting engine runs on: a FIFO of encoded positions that refuses to hold the same
 * position twice.
 *
 * <p>Merges the two ancestors. Phosphor's {@code PooledLongQueue} contributes the storage — a chain of
 * pooled 1024-long segments, so a queue that swells during world generation and empties afterwards
 * hands its memory back instead of holding a multi-megabyte array for the session. Alfheim contributes
 * the deduplication, which is where the actual saving is: a bulk edit schedules the same position from
 * every neighbour that touches it, and without this each one is evaluated separately.
 *
 * <h2>When the deduplication set is reset</h2>
 *
 * <p>The invariant is simple to state and easy to get backwards: <b>the set must be empty at the moment
 * the next cycle's enqueues begin.</b> Getting it wrong does not corrupt anything — it silently drops a
 * position that was updated in two consecutive cycles, which shows up much later as one stale block.
 *
 * <p>Where {@link #resetDeduplication()} goes therefore depends on when the enqueues happen. The
 * engine fills a light level's queue during the levels above it and then drains it, so it resets
 * before draining. The renderer's queue is only filled after its drain, so it resets after.
 *
 * <p>Not thread-safe, with one deliberate exception: {@link #isEmpty()} reads a volatile flag so the
 * engine can skip acquiring its lock from another thread.
 */
public final class DeduplicatedLongQueue {
    private static final int SEGMENT_SIZE = 1 << 10;

    /**
     * Entry count past which the deduplication set is replaced rather than cleared.
     *
     * <p>{@code LongOpenHashSet.clear()} keeps the backing array, which is the right trade for the
     * steady state — but a single world-generation burst can grow one set to millions of entries, and
     * keeping that array for the rest of the session costs more than the occasional reallocation.
     *
     * <p>32768 entries is roughly a 512 KiB table. Set much lower and a world that routinely exceeds it
     * reallocates every pass; much higher and one burst is remembered for the session.
     */
    private static final int RETAINED_SET_CAPACITY = 1 << 15;

    private final Pool pool;
    private final int initialSetCapacity;

    /**
     * Whether to reject repeats.
     *
     * <p>A kill switch rather than a tuning knob — deduplication is strictly a win — but it decides
     * whether a lighting bug can be bisected without a rebuild, so it is worth the branch. Final, so
     * the JIT hoists it out of the loop.
     */
    private final boolean deduplicate;

    private LongOpenHashSet seen;

    private Segment head;
    private Segment tail;

    /** Read cursor into {@link #head}. */
    private int headIndex;

    private int size;

    /**
     * Whether the queue holds nothing.
     *
     * <p>Volatile so {@link #isEmpty()} is safe to call from a thread that does not hold the engine's
     * lock. Writes to volatile fields are not free, so it is only written on the transitions.
     */
    private volatile boolean empty = true;

    public DeduplicatedLongQueue(Pool pool, int initialCapacity, boolean deduplicate) {
        this.pool = pool;
        this.initialSetCapacity = initialCapacity;
        this.deduplicate = deduplicate;
        this.seen = deduplicate ? new LongOpenHashSet(initialCapacity) : null;
    }

    /**
     * Appends a value unless it is already pending.
     *
     * @return whether the value was appended
     */
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

    /** Removes and returns the oldest value. Undefined if the queue is empty. */
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

    public boolean isEmpty() {
        return this.empty;
    }

    public int size() {
        return this.size;
    }

    /**
     * Forgets which values have been seen, so a position can be scheduled again.
     *
     * <p>Place the call so the set is empty when the next cycle's enqueues start; see the class comment.
     */
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

    /**
     * Segment store shared by every queue belonging to one engine.
     *
     * <p>Sharing matters: the darkening and brightening queues are used in strict succession, one
     * light level at a time, so the segments one level frees are immediately reusable by the next
     * instead of each of the thirty-four queues holding its own high-water mark.
     */
    public static final class Pool {
        /**
         * Ceiling on retained segments, not on queue size — a queue can always allocate past this, it
         * just will not get the memory back into the pool.
         *
         * <p>1024 segments is 8 MiB, well above any realistic high-water mark now that repeats are
         * dropped before they reach the queue, and low enough that one pathological burst cannot leave
         * the pool holding a large arena for the rest of the session.
         */
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
