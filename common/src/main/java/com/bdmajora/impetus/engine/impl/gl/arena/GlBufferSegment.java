package com.bdmajora.impetus.engine.impl.gl.arena;

public class GlBufferSegment {
    private final GlBufferArena arena;

    private boolean free = false;

    private int offset;
    private int length;

    private GlBufferSegment next;
    private GlBufferSegment prev;

    public GlBufferSegment(GlBufferArena arena, int offset, int length) {
        this.arena = arena;
        this.offset = offset;
        this.length = length;
    }

    // Returns this segment to its arena
    public void delete() {
        this.arena.free(this);
    }

    // Offset plus length
    protected int getEnd() {
        return this.offset + this.length;
    }

    // Bytes
    public int getLength() {
        return this.length;
    }

    // Arena-internal
    protected void setLength(int len) {
        if (len <= 0) {
            throw new IllegalArgumentException("len <= 0");
        }

        this.length = len;
    }

    // Byte offset into the arena buffer
    public int getOffset() {
        return this.offset;
    }

    // Arena-internal
    protected void setOffset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("start < 0");
        }

        this.offset = offset;
    }

    // Arena-internal
    protected void setFree(boolean free) {
        this.free = free;
    }

    // Whether on the free list
    protected boolean isFree() {
        return this.free;
    }

    // Linked-list maintenance
    protected void setNext(GlBufferSegment next) {
        this.next = next;
    }

    // Following segment by address
    protected GlBufferSegment getNext() {
        return this.next;
    }

    // Preceding segment by address
    protected GlBufferSegment getPrev() {
        return this.prev;
    }

    // Linked-list maintenance
    protected void setPrev(GlBufferSegment prev) {
        this.prev = prev;
    }

    // Absorbs a following free segment
    protected void mergeInto(GlBufferSegment entry) {
        this.setLength(this.getLength() + entry.getLength());
        this.setNext(entry.getNext());

        if (this.getNext() != null) {
            this.getNext()
                    .setPrev(this);
        }
    }
}
