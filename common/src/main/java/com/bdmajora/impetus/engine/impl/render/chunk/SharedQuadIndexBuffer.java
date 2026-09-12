package com.bdmajora.impetus.engine.impl.render.chunk;

import com.bdmajora.impetus.engine.impl.gl.buffer.GlBuffer;
import com.bdmajora.impetus.engine.impl.gl.buffer.GlBufferMapFlags;
import com.bdmajora.impetus.engine.impl.gl.buffer.GlBufferUsage;
import com.bdmajora.impetus.engine.impl.gl.buffer.GlMutableBuffer;
import com.bdmajora.impetus.engine.impl.gl.device.CommandList;
import com.bdmajora.impetus.engine.impl.gl.util.EnumBitField;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.sorting.ChunkPrimitiveType;

public class SharedQuadIndexBuffer {
    private final GlMutableBuffer buffer;
    private final ChunkPrimitiveType primitiveType;

    private int maxPrimitives;

    public SharedQuadIndexBuffer(CommandList commandList, ChunkPrimitiveType primitiveType) {
        this.buffer = commandList.createMutableBuffer();
        this.primitiveType = primitiveType;
    }

    // Grows if a draw needs more indices than exist
    public void ensureCapacity(CommandList commandList, int elementCount) {
        int primitiveCount = elementCount / primitiveType.getIndexBufferElementsPerPrimitive();

        if (primitiveCount > this.maxPrimitives) {
            this.grow(commandList, this.getNextSize(primitiveCount));
        }
    }

    // Doubles until large enough
    private int getNextSize(int primitiveCount) {
        return Math.max(this.maxPrimitives * 2, primitiveCount + 16384);
    }

    // Regenerates the sequential index pattern at the new size
    private void grow(CommandList commandList, int primitiveCount) {
        var bufferSize = primitiveType.getIndexBufferSize(primitiveCount);

        commandList.allocateStorage(this.buffer, bufferSize, GlBufferUsage.STATIC_DRAW);

        var mapped = commandList.mapBuffer(this.buffer, 0, bufferSize, EnumBitField.of(GlBufferMapFlags.INVALIDATE_BUFFER, GlBufferMapFlags.WRITE, GlBufferMapFlags.UNSYNCHRONIZED));
        this.primitiveType.generateSimpleIndexBuffer(mapped.getMemoryBuffer(), primitiveCount);

        commandList.unmap(mapped);

        this.maxPrimitives = primitiveCount;
    }


    // For binding
    public GlBuffer getBufferObject() {
        return this.buffer;
    }

    // Frees the buffer
    public void delete(CommandList commandList) {
        commandList.deleteBuffer(this.buffer);
    }
}
