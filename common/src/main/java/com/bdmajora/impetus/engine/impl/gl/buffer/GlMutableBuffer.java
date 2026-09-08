package com.bdmajora.impetus.engine.impl.gl.buffer;

// OpenGL 1.5+ buffer whose storage can be reallocated without recreating the buffer object
public class GlMutableBuffer extends GlBuffer {
    private long size = 0L;

    public GlMutableBuffer() {
        super();
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getSize() {
        return this.size;
    }
}
