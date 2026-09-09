package com.bdmajora.impetus.lwjgl.lwjgl3;

import com.bdmajora.impetus.lwjgl.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

// The LWJGL3 backend of MemoryStack, a thin wrapper over org.lwjgl.system.MemoryStack
// LWJGL3 already ships a stack allocator, so unlike the LWJGL2 side this delegates rather than reimplementing one
public class LWJGL3MemoryStack extends MemoryStack {
    private final org.lwjgl.system.MemoryStack delegate;

    public LWJGL3MemoryStack(org.lwjgl.system.MemoryStack delegate) {
        this.delegate = delegate;
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public long getPointer() {
        return delegate.getPointer();
    }

    @Override
    public void setPointer(long pointer) {
        // LWJGL3's MemoryStack uses int for stack pointer positions (stack is max ~8KB)
        delegate.setPointer((int) pointer);
    }

    @Override
    public long getAddress() {
        return delegate.getAddress();
    }

    @Override
    public int getSize() {
        return delegate.getSize();
    }

    // ===================== MALLOC OPERATIONS =====================

    @Override
    public ByteBuffer malloc(int size) {
        return delegate.malloc(size);
    }

    @Override
    public ShortBuffer mallocShort(int count) {
        return delegate.mallocShort(count);
    }

    @Override
    public IntBuffer mallocInt(int count) {
        return delegate.mallocInt(count);
    }

    @Override
    public LongBuffer mallocLong(int count) {
        return delegate.mallocLong(count);
    }

    @Override
    public FloatBuffer mallocFloat(int count) {
        return delegate.mallocFloat(count);
    }

    // ===================== CALLOC OPERATIONS =====================

    @Override
    public ByteBuffer calloc(int size) {
        return delegate.calloc(size);
    }

    @Override
    public ShortBuffer callocShort(int count) {
        return delegate.callocShort(count);
    }

    @Override
    public IntBuffer callocInt(int count) {
        return delegate.callocInt(count);
    }

    @Override
    public LongBuffer callocLong(int count) {
        return delegate.callocLong(count);
    }

    @Override
    public FloatBuffer callocFloat(int count) {
        return delegate.callocFloat(count);
    }

    // ===================== POINTER OPERATIONS =====================

    @Override
    public long nmalloc(int size) {
        return delegate.nmalloc(size);
    }

    @Override
    public long nmalloc(int alignment, int size) {
        return delegate.nmalloc(alignment, size);
    }

    @Override
    public long ncalloc(int alignment, int count, int size) {
        return delegate.ncalloc(alignment, count, size);
    }
}
