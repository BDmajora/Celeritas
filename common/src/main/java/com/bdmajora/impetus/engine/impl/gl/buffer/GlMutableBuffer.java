package com.bdmajora.impetus.engine.impl.gl.buffer;

// OpenGL 1.5+ buffer whose storage can be reallocated without recreating the buffer object
public class GlMutableBuffer extends GlBuffer {
    private long size = 0L;

    public GlMutableBuffer() {
        super();
    }

    // Recorded on each glBufferData
    public void setSize(long size) {
        this.size = size;
    }

    // Current allocation
    public long getSize() {
        return this.size;
    }
}
