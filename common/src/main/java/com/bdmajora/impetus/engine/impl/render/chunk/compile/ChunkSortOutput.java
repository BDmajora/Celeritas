package com.bdmajora.impetus.engine.impl.render.chunk.compile;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import com.bdmajora.impetus.engine.impl.common.util.NativeBuffer;
import com.bdmajora.impetus.engine.impl.render.chunk.RenderSection;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;

public class ChunkSortOutput extends ChunkTaskOutput {
    // A re-sorted index buffer for one translucent pass
    public record SortedMesh(NativeBuffer indexData) {}

    public final Reference2ReferenceMap<TerrainRenderPass, SortedMesh> meshes;

    public ChunkSortOutput(RenderSection render, int buildTime, Reference2ReferenceMap<TerrainRenderPass, SortedMesh> meshes) {
        super(render, buildTime);
        this.meshes = meshes;
    }

    // Frees the index buffers if never consumed
    @Override
    public void delete() {
        for (SortedMesh data : this.meshes.values()) {
            data.indexData().free();
        }
    }
}
