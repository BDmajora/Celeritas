package com.bdmajora.impetus.engine.impl.gl.arena;

import com.bdmajora.impetus.engine.impl.common.util.NativeBuffer;
import org.jetbrains.annotations.Nullable;

public class PendingUpload {
    private final NativeBuffer data;
    private GlBufferSegment result;

    private PendingUpload(NativeBuffer data) {
        this.data = data;
    }

    public static @Nullable PendingUpload of(@Nullable NativeBuffer data) {
        if (data == null) {
            return null;
        }
        return new PendingUpload(data);
    }

    // The bytes to upload
    public NativeBuffer getDataBuffer() {
        return this.data;
    }

    // Set by the arena once allocated
    protected void setResult(GlBufferSegment result) {
        if (this.result != null) {
            throw new IllegalStateException("Result already provided");
        }

        this.result = result;
    }

    // Where the data landed; throws if not yet uploaded
    public GlBufferSegment getResult() {
        if (this.result == null) {
            throw new IllegalStateException("Result not computed");
        }

        return this.result;
    }

    // Bytes
    public int getLength() {
        return this.data.getLength();
    }
}
