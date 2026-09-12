package com.bdmajora.impetus.engine.impl.gl.state;

import com.bdmajora.impetus.engine.impl.gl.array.GlVertexArray;
import com.bdmajora.impetus.engine.impl.gl.buffer.GlBuffer;
import com.bdmajora.impetus.engine.impl.gl.buffer.GlBufferTarget;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public class GlStateTracker {
    private static final int UNASSIGNED_HANDLE = -1;

    private final int[] bufferState = new int[GlBufferTarget.COUNT];
    private int vertexArrayState;

    public GlStateTracker() {

    }

    // Clears the cached binding so a reused name is not mistaken for still bound
    public void notifyVertexArrayDeleted(GlVertexArray vertexArray) {
        if (this.vertexArrayState == vertexArray.handle()) {
            this.vertexArrayState = UNASSIGNED_HANDLE;
        }
    }

    // Clears any cached binding of it
    public void notifyBufferDeleted(GlBuffer buffer) {
        for (GlBufferTarget target : GlBufferTarget.VALUES) {
            if (this.bufferState[target.ordinal()] == buffer.handle()) {
                this.bufferState[target.ordinal()] = UNASSIGNED_HANDLE;
            }
        }
    }

    // Records and returns whether a real bind is needed
    public boolean makeBufferActive(GlBufferTarget target, @Nullable GlBuffer buffer) {
        int handle = buffer == null ? UNASSIGNED_HANDLE : buffer.handle();

        boolean changed = this.bufferState[target.ordinal()] != handle;

        if (changed) {
            this.bufferState[target.ordinal()] = handle;
        }

        return changed;
    }

    // Records and returns whether a real bind is needed
    public boolean makeVertexArrayActive(GlVertexArray array) {
        int handle = array == null ? GlVertexArray.NULL_ARRAY_ID : array.handle();
        boolean changed = this.vertexArrayState != handle;

        if (changed) {
            this.vertexArrayState = handle;

            Arrays.fill(this.bufferState, UNASSIGNED_HANDLE);
        }

        return changed;
    }

    // Forgets everything, for when GL state was changed behind our back
    public void clear() {
        Arrays.fill(this.bufferState, -1);
        this.vertexArrayState = -1;
    }
}
