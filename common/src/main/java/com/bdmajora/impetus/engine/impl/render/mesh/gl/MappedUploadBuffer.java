package com.bdmajora.impetus.engine.impl.render.mesh.gl;

import com.bdmajora.impetus.lwjgl.GL30;
import com.bdmajora.impetus.lwjgl.GL44;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The staging side of every upload: persistently mapped, client-resident, write-only
// GL_CLIENT_STORAGE_BIT asks the driver to keep it in host memory, because the CPU writes it every frame and the
// GPU only ever reads it once, during the copy into a BindlessBuffer
// Explicit flush rather than coherent mapping — one flush per allocation beats an implicit flush per write
public class MappedUploadBuffer {
    private static final int STORAGE_FLAGS = GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_CLIENT_STORAGE_BIT | GL30.GL_MAP_WRITE_BIT;
    private static final int MAP_FLAGS = GL44.GL_MAP_PERSISTENT_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT
            | GL30.GL_MAP_FLUSH_EXPLICIT_BIT | GL30.GL_MAP_WRITE_BIT;

    private final int id;
    private final long size;
    private final long clientAddress;
    private boolean deleted;

    public MappedUploadBuffer(long size) {
        this.size = size;
        this.id = LWJGL.glCreateBuffers();
        LWJGL.glNamedBufferStorage(this.id, size, STORAGE_FLAGS);
        this.clientAddress = LWJGL.nglMapNamedBufferRange(this.id, 0L, size, MAP_FLAGS);

        if (this.clientAddress == 0L) {
            throw new IllegalStateException("Failed to persistently map the terrain upload buffer");
        }
    }

    public int getId() {
        return this.id;
    }

    public long getSize() {
        return this.size;
    }

    // Base of the mapping; callers write at clientAddress + their own offset and never past size
    public long getClientAddress() {
        return this.clientAddress;
    }

    public void flush(long offset, long length) {
        LWJGL.glFlushMappedNamedBufferRange(this.id, offset, length);
    }

    public void delete() {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
        LWJGL.glUnmapNamedBuffer(this.id);
        LWJGL.glDeleteBuffers(this.id);
    }
}
