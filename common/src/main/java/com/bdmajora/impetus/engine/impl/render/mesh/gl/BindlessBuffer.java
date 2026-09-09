package com.bdmajora.impetus.engine.impl.render.mesh.gl;

import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL15;
import com.bdmajora.impetus.lwjgl.GL30;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Immutable device-local storage made resident, so shaders reach it through a raw 64-bit pointer instead of a
// binding point
// Nothing here is ever mapped on the CPU: writes arrive through UploadStream's staging copies, which is what lets
// the driver keep the allocation in the fastest memory it has
public class BindlessBuffer implements DeviceBuffer {
    private final int id;
    private final long size;
    private final long deviceAddress;
    private boolean deleted;

    public BindlessBuffer(long size) {
        this.size = size;
        this.id = LWJGL.glCreateBuffers();
        // No storage flags at all: no client access of any kind, the strongest hint the driver gets
        LWJGL.glNamedBufferStorage(this.id, size, 0);
        LWJGL.glMakeNamedBufferResidentNV(this.id, GL15.GL_READ_WRITE);
        this.deviceAddress = LWJGL.glGetNamedBufferGpuAddressNV(this.id);

        if (this.deviceAddress == 0L) {
            throw new IllegalStateException("Driver returned a null GPU address for a resident buffer");
        }
    }

    @Override
    public int getId() {
        return this.id;
    }

    @Override
    public long getSize() {
        return this.size;
    }

    @Override
    public long getDeviceAddress() {
        return this.deviceAddress;
    }

    // Zeroes the whole buffer on the GPU; no staging copy and no CPU-side array
    public void clear() {
        LWJGL.glClearNamedBufferDataZero(this.id, GL30.GL_R8UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE);
    }

    // Zeroes a byte range; used to wipe a region's visibility bytes the frame it leaves the frustum, so stale
    // "visible" flags cannot resurrect it next frame
    public void clearRange(long offset, long length) {
        LWJGL.glClearNamedBufferSubDataZero(this.id, GL30.GL_R8UI, offset, length, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE);
    }

    @Override
    public void delete() {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
        LWJGL.glMakeNamedBufferNonResidentNV(this.id);
        LWJGL.glDeleteBuffers(this.id);
    }
}
