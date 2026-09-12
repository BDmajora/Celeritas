package com.bdmajora.impetus.engine.impl.render.chunk.multidraw;

import com.bdmajora.impetus.engine.impl.gl.device.CommandList;
import com.bdmajora.impetus.engine.impl.gl.device.DrawCommandList;
import com.bdmajora.impetus.engine.impl.gl.device.MultiDrawBatch;
import com.bdmajora.impetus.engine.impl.gl.tessellation.GlIndexType;
import com.bdmajora.impetus.engine.impl.gl.tessellation.GlPrimitiveType;
import com.bdmajora.impetus.engine.impl.gl.tessellation.GlTessellation;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;
import com.bdmajora.impetus.engine.impl.render.chunk.data.SectionRenderDataUnsafe;
import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

public record DirectMultiDrawEmitter(MultiDrawBatch batch) implements MultiDrawEmitter {
    public DirectMultiDrawEmitter() {
        this(new MultiDrawBatch(MAX_COMMAND_COUNT));
    }

    // Appends one draw per visible facing of a section
    @Override
    @SuppressWarnings("IntegerMultiplicationImplicitCastToLong")
    public void addDrawCommands(long pMeshData, int mask, int indexPointerMask) {
        var batch = this.batch;
        final var pBaseVertex = batch.pBaseVertex;
        final var pElementCount = batch.pElementCount;
        final var pElementPointer = batch.pElementPointer;

        int size = batch.size;

        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            LWJGL.memPutInt(pBaseVertex + (size << 2), SectionRenderDataUnsafe.getVertexOffset(pMeshData, facing));
            LWJGL.memPutInt(pElementCount + (size << 2), SectionRenderDataUnsafe.getElementCount(pMeshData, facing));
            LWJGL.memPutAddress(pElementPointer + (size << 3), SectionRenderDataUnsafe.getIndexOffset(pMeshData, facing) & indexPointerMask);

            size += (mask >> facing) & 1;
        }

        batch.size = size;
    }

    // glMultiDrawElementsBaseVertex over the batch
    @Override
    public void executeBatch(CommandList commandList, GlTessellation tessellation, GlPrimitiveType primitiveType) {
        try (DrawCommandList drawCommandList = commandList.beginTessellating(tessellation)) {
            drawCommandList.multiDrawElementsBaseVertex(batch, primitiveType, GlIndexType.UNSIGNED_INT);
        }
    }

    // Largest index count in the batch, for the shared index buffer
    @Override
    public int getIndexBufferSize() {
        return this.batch.getIndexBufferSize();
    }

    // No draws
    @Override
    public boolean isEmpty() {
        return this.batch.isEmpty();
    }

    // Resets for the next region
    @Override
    public void clear() {
        this.batch.clear();
    }

    // Frees the native arrays
    @Override
    public void delete() {
        this.batch.delete();
    }
}
