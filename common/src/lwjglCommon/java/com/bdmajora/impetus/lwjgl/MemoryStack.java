package com.bdmajora.impetus.lwjgl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

// A thread-local stack allocator, for the short-lived native buffers GL calls need
// Always use it through try-with-resources — LWJGL.stackPush() opens a frame and close() pops it, so a buffer
// allocated inside is freed automatically when the block ends
// Anything allocated here must NOT outlive the block: the memory is reused by the next frame on that thread
public abstract class MemoryStack implements AutoCloseable {

    // Pushes a frame on the current backend's stack; pair with close
    public static MemoryStack stackPush() {
        return LWJGLServiceProvider.LWJGL.stackPush();
    }

    @Override
    public abstract void close();

    public abstract long getPointer();
    public abstract void setPointer(long pointer);
    public abstract long getAddress();
    public abstract int getSize();

    // ===================== MALLOC OPERATIONS =====================

    public abstract ByteBuffer malloc(int size);
    public abstract ShortBuffer mallocShort(int count);
    public abstract IntBuffer mallocInt(int count);
    public abstract LongBuffer mallocLong(int count);
    public abstract FloatBuffer mallocFloat(int count);

    // ===================== CALLOC OPERATIONS =====================

    public abstract ByteBuffer calloc(int size);
    public abstract ShortBuffer callocShort(int count);
    public abstract IntBuffer callocInt(int count);
    public abstract LongBuffer callocLong(int count);
    public abstract FloatBuffer callocFloat(int count);

    // ===================== PRIMITIVE ALLOCATIONS =====================

    public IntBuffer ints(int value) {
        IntBuffer buf = mallocInt(1);
        buf.put(0, value);
        return buf;
    }

    // Allocates and fills
    public IntBuffer ints(int... values) {
        IntBuffer buf = mallocInt(values.length);
        buf.put(values).flip();
        return buf;
    }

    // Single-float convenience
    public FloatBuffer floats(float value) {
        FloatBuffer buf = mallocFloat(1);
        buf.put(0, value);
        return buf;
    }

    // Allocates and fills
    public FloatBuffer floats(float... values) {
        FloatBuffer buf = mallocFloat(values.length);
        buf.put(values).flip();
        return buf;
    }

    // ===================== POINTER OPERATIONS =====================

    public abstract long nmalloc(int size);
    public abstract long nmalloc(int alignment, int size);
    public abstract long ncalloc(int alignment, int count, int size);
}
