package com.bdmajora.impetus.engine.impl.render.chunk.compile;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import com.bdmajora.impetus.engine.impl.render.chunk.RenderSection;
import com.bdmajora.impetus.engine.impl.render.chunk.data.BuiltRenderSectionData;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;
import com.bdmajora.impetus.engine.impl.render.chunk.data.BuiltSectionMeshParts;

// Everything a finished chunk rebuild produced that still has to be processed or uploaded on the main thread
// A worker cannot upload its own result, so the output crosses the thread boundary as this object
// A task cancelled after it finished but before its result was processed has that result DISCARDED rather than
// uploaded — otherwise a section the player already left would be re-added to the draw lists
public class ChunkBuildOutput extends ChunkTaskOutput {
    public final BuiltRenderSectionData info;
    public final Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes;

    public ChunkBuildOutput(RenderSection render, BuiltRenderSectionData info, Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes, int buildTime) {
        super(render, buildTime);
        this.info = info;
        this.meshes = meshes;

        if (this.info != null) {
            this.info.bake();
        }
    }

    @Override
    public void delete() {
        for (BuiltSectionMeshParts data : this.meshes.values()) {
            data.free();
        }
    }
}
