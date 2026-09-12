package com.bdmajora.impetus.engine.impl.render.mesh.util;

import com.bdmajora.impetus.engine.impl.render.mesh.MeshShaderSupport;
import com.bdmajora.impetus.engine.impl.render.mesh.gl.BindlessBuffer;
import com.bdmajora.impetus.engine.impl.render.mesh.gl.DeviceBuffer;
import com.bdmajora.impetus.engine.impl.render.mesh.gl.SparseBindlessBuffer;

// Every section's geometry in one buffer addressed by quad index (mesh shader skips a multiply); sparse-backed where pages commit properly so fragmentation costs address space not VRAM
public class QuadArena {
    // Sparse address space, deliberately far beyond real VRAM: it is virtual, never resident, and a huge span means the allocator never compacts
    private static final long SPARSE_ADDRESS_SPACE = 80L * 1024L * 1024L * 1024L;

    private final SegmentedAllocator allocator = new SegmentedAllocator();
    private final DeviceBuffer buffer;
    private final int bytesPerVertex;
    private final long denseCapacity;

    private long liveQuads;

    public QuadArena(long memoryBudget, int bytesPerVertex) {
        this.bytesPerVertex = bytesPerVertex;

        if (MeshShaderSupport.supportsSparseGeometry()) {
            this.buffer = new SparseBindlessBuffer(SPARSE_ADDRESS_SPACE);
            this.denseCapacity = 0L;
        } else {
            this.buffer = new BindlessBuffer(memoryBudget);
            this.denseCapacity = memoryBudget;
            this.allocator.setLimit(memoryBudget / (4L * bytesPerVertex));
        }

        // Burn quad 0 so a zeroed section header cannot be mistaken for geometry starting at the very beginning of the arena
        this.alloc(1);
    }

    // The backing GPU buffer
    public DeviceBuffer getBuffer() {
        return this.buffer;
    }

    // Returns the quad index of the allocation, or SegmentedAllocator.OUT_OF_SPACE when the arena is full
    public int alloc(int quadCount) {
        long address = this.allocator.alloc(quadCount);

        if (address == SegmentedAllocator.OUT_OF_SPACE) {
            return (int) SegmentedAllocator.OUT_OF_SPACE;
        }

        this.liveQuads += quadCount;

        if (this.buffer instanceof SparseBindlessBuffer sparse) {
            sparse.ensureCommitted(byteOffset((int) address), byteLength(quadCount));
        }

        return (int) address;
    }

    // Returns a quad range to the allocator
    public void free(int quadAddress) {
        int quadCount = this.allocator.free(quadAddress);
        this.liveQuads -= quadCount;

        if (this.buffer instanceof SparseBindlessBuffer sparse) {
            sparse.release(byteOffset(quadAddress), byteLength(quadCount));
        }
    }

    // Whether an existing allocation is exactly the right size for a rebuild, so a same-count rebuild keeps its address and skips the free/alloc round trip
    public boolean canReuse(int quadAddress, int quadCount) {
        return this.allocator.getSize(quadAddress) == quadCount;
    }

    // Reserves staging space for the whole allocation at quadAddress and returns where to write it
    public long beginUpload(UploadStream stream, int quadAddress) {
        long quadCount = this.allocator.getSize(quadAddress);
        return stream.upload(this.buffer, byteOffset(quadAddress), byteLength((int) quadCount));
    }

    // What the arena physically costs right now: committed pages on the sparse path, not the address space
    public long getResidentBytes() {
        if (this.buffer instanceof SparseBindlessBuffer sparse) {
            return sparse.getCommittedBytes();
        }
        return this.denseCapacity;
    }

    // For the debug screen
    public long getUsedBytes() {
        return byteLength((int) this.liveQuads);
    }

    // Frees the buffer
    public void delete() {
        this.buffer.delete();
    }

    // Quad indices are unsigned, so the arena can address twice what a signed int would reach
    private long byteOffset(int quadAddress) {
        return Integer.toUnsignedLong(quadAddress) * 4L * this.bytesPerVertex;
    }

    // Quads to bytes at the quad stride
    private long byteLength(int quadCount) {
        return (long) quadCount * 4L * this.bytesPerVertex;
    }
}
