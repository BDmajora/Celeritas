package com.bdmajora.impetus.engine.impl.render.mesh.gl;

import com.bdmajora.impetus.lwjgl.GL15;
import com.bdmajora.impetus.lwjgl.GLNv;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A resident buffer with an enormous address space whose physical pages are committed on demand
// Terrain geometry lives in one of these so section allocation is pointer arithmetic in a flat address space,
// rather than per-region arenas that have to be compacted once they fragment
public class SparseBindlessBuffer implements DeviceBuffer {
    // 1 MB, not the driver's minimum page size
    // Smaller pages fragment the driver's own physical allocator badly enough that commitment cost dominates
    public static final long PAGE_SIZE = 1L << 20;

    private final int id;
    private final long size;
    private final long deviceAddress;
    private boolean deleted;

    // Page index -> number of live allocations touching it
    // A page is released only when the last allocation overlapping it goes away, which matters because
    // allocations are quad-granular and neighbouring sections routinely share a page
    private final Int2IntOpenHashMap pageRefCounts = new Int2IntOpenHashMap();

    public SparseBindlessBuffer(long requestedSize) {
        this.size = alignUp(requestedSize, PAGE_SIZE);
        this.id = LWJGL.glCreateBuffers();
        LWJGL.glNamedBufferStorage(this.id, this.size, GLNv.GL_SPARSE_STORAGE_BIT_ARB);
        LWJGL.glMakeNamedBufferResidentNV(this.id, GL15.GL_READ_WRITE);
        this.deviceAddress = LWJGL.glGetNamedBufferGpuAddressNV(this.id);

        if (this.deviceAddress == 0L) {
            throw new IllegalStateException("Driver returned a null GPU address for a resident sparse buffer");
        }
    }

    // Rounds up to a multiple
    public static long alignUp(long value, long alignment) {
        long remainder = value % alignment;
        return remainder == 0 ? value : value + (alignment - remainder);
    }

    // GL name
    @Override
    public int getId() {
        return this.id;
    }

    // Virtual size; only committed pages are backed
    @Override
    public long getSize() {
        return this.size;
    }

    // GPU virtual address
    @Override
    public long getDeviceAddress() {
        return this.deviceAddress;
    }

    // Commits every page the byte range touches, taking a reference on each
    public void ensureCommitted(long offset, long length) {
        int firstPage = (int) (offset / PAGE_SIZE);
        int lastPage = (int) ((offset + length + PAGE_SIZE - 1) / PAGE_SIZE);

        commit(firstPage, lastPage - firstPage, true);

        for (int page = firstPage; page < lastPage; page++) {
            this.pageRefCounts.addTo(page, 1);
        }
    }

    // Drops a reference on every page the range touches, releasing the ones that hit zero
    public void release(long offset, long length) {
        int firstPage = (int) (offset / PAGE_SIZE);
        int lastPage = (int) ((offset + length + PAGE_SIZE - 1) / PAGE_SIZE);

        for (int page = firstPage; page < lastPage; page++) {
            int remaining = this.pageRefCounts.get(page) - 1;

            if (remaining > 0) {
                this.pageRefCounts.put(page, remaining);
            } else {
                this.pageRefCounts.remove(page);
                commit(page, 1, false);
            }
        }
    }

    // Physically resident pages, i.e. what this buffer actually costs in VRAM regardless of its address space
    public int getCommittedPages() {
        return this.pageRefCounts.size();
    }

    // Backed memory, for the debug screen
    public long getCommittedBytes() {
        return (long) this.pageRefCounts.size() * PAGE_SIZE;
    }

    // Makes non-resident, then deletes
    @Override
    public void delete() {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
        LWJGL.glMakeNamedBufferNonResidentNV(this.id);
        LWJGL.glDeleteBuffers(this.id);
    }

    // glBufferPageCommitmentARB over a page range
    private void commit(int firstPage, int pageCount, boolean commit) {
        if (pageCount <= 0) {
            return;
        }
        // Page commitment has no DSA form, so the buffer has to be bound; ARRAY_BUFFER is the least intrusive
        // target because nothing in the mesh pipeline draws from a bound vertex array
        LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.id);
        LWJGL.glBufferPageCommitmentARB(GL15.GL_ARRAY_BUFFER, firstPage * PAGE_SIZE, pageCount * PAGE_SIZE, commit);
        LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }
}
