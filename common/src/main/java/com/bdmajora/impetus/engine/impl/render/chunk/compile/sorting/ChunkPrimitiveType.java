package com.bdmajora.impetus.engine.impl.render.chunk.compile.sorting;

import com.bdmajora.impetus.engine.impl.render.chunk.sorting.TranslucentQuadAnalyzer;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;

public interface ChunkPrimitiveType {
    default int getIndexBufferSize(int numPrimitives) {
        return numPrimitives * getIndexBufferElementsPerPrimitive() * 4;
    }

    // the number of vertices in a primitive, e.g. 4 for quads, 3 for triangles
    int getVerticesPerPrimitive();

    // the number of index buffer elements per primitive, e.g. 6 for quads, 3 for triangles
    int getIndexBufferElementsPerPrimitive();

    // generates a "simple" index buffer, rendering numPrimitives primitives in the order they appear in
    // the vertex buffer
    // the caller is responsible for providing a buffer of the size given by getIndexBufferSize(int)
    // indexBuffer is a NativeBuffer to be populated with 32-bit integers
    void generateSimpleIndexBuffer(ByteBuffer indexBuffer, int numPrimitives);

    // generates a sorted index buffer for numPrimitives primitives, with data on those primitives
    // supplied in chunkData
    // the caller is responsible for providing a buffer of the size given by getIndexBufferSize(int)
    // the camera position is subchunk-relative, so it compares directly against the vertex positions
    // held in the SortState
    // indexBuffer is a NativeBuffer to be populated with 32-bit integers, and x/y/z are the camera
    void generateSortedIndexBuffer(ByteBuffer indexBuffer, int numPrimitives, @Nullable TranslucentQuadAnalyzer.SortState chunkData, float x, float y, float z);

    default List<String> getDefines() {
        return List.of();
    }
}
