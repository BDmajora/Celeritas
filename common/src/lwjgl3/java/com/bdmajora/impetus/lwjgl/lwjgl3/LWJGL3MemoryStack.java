package com.bdmajora.impetus.lwjgl.lwjgl3;

import com.bdmajora.impetus.lwjgl.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

// LWJGL3 backend of MemoryStack: LWJGL3 already ships a stack allocator, so this just delegates to org.lwjgl.system.MemoryStack
public class LWJGL3MemoryStack extends MemoryStack {
    private final org.lwjgl.system.MemoryStack delegate;

    public LWJGL3MemoryStack(org.lwjgl.system.MemoryStack delegate) {
        this.delegate = delegate;
    }

    // Pops the frame, so try-with-resources releases everything allocated since push
    @Override
    public void close() {
        delegate.close();
    }

    // Current stack pointer, for manual save and restore
    @Override
    public long getPointer() {
        return delegate.getPointer();
    }

    // Restores a saved stack pointer
    @Override
    public void setPointer(long pointer) {
        // LWJGL3's MemoryStack uses int for stack pointer positions (stack is max ~8KB)
        delegate.setPointer((int) pointer);
    }

    // Base address of the stack memory
    @Override
    public long getAddress() {
        return delegate.getAddress();
    }

    // Total stack capacity
    @Override
    public int getSize() {
        return delegate.getSize();
    }

    // ===================== MALLOC OPERATIONS =====================

    @Override
    public ByteBuffer malloc(int size) {
        return delegate.malloc(size);
    }

    // Uninitialised shorts
    @Override
    public ShortBuffer mallocShort(int count) {
        return delegate.mallocShort(count);
    }

    // Uninitialised ints
    @Override
    public IntBuffer mallocInt(int count) {
        return delegate.mallocInt(count);
    }

    // Uninitialised longs
    @Override
    public LongBuffer mallocLong(int count) {
        return delegate.mallocLong(count);
    }

    // Uninitialised floats
    @Override
    public FloatBuffer mallocFloat(int count) {
        return delegate.mallocFloat(count);
    }

    // ===================== CALLOC OPERATIONS =====================

    @Override
    public ByteBuffer calloc(int size) {
        return delegate.calloc(size);
    }

    // Zeroed shorts
    @Override
    public ShortBuffer callocShort(int count) {
        return delegate.callocShort(count);
    }

    // Zeroed ints
    @Override
    public IntBuffer callocInt(int count) {
        return delegate.callocInt(count);
    }

    // Zeroed longs
    @Override
    public LongBuffer callocLong(int count) {
        return delegate.callocLong(count);
    }

    // Zeroed floats
    @Override
    public FloatBuffer callocFloat(int count) {
        return delegate.callocFloat(count);
    }

    // ===================== POINTER OPERATIONS =====================

    @Override
    public long nmalloc(int size) {
        return delegate.nmalloc(size);
    }

    // Raw aligned allocation returning an address
    @Override
    public long nmalloc(int alignment, int size) {
        return delegate.nmalloc(alignment, size);
    }

    // Raw aligned zeroed allocation returning an address
    @Override
    public long ncalloc(int alignment, int count, int size) {
        return delegate.ncalloc(alignment, count, size);
    }
}
