package com.bdmajora.impetus.engine.impl.render.chunk;

import com.bdmajora.impetus.engine.impl.gl.device.CommandList;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.ChunkRenderListIterable;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;
import com.bdmajora.impetus.engine.impl.render.viewport.CameraTransform;

// The chunk render backend manages the graphics resource state of chunk render containers: uploading their data and rendering them
public interface ChunkRenderer {
    // Renders the render lists for the given pass to the active framebuffer; occlusionCamera drives block face culling and camera carries the chunk offsets
    void render(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists,
                TerrainRenderPass pass, CameraTransform occlusionCamera, CameraTransform camera);

    // deletes this render backend and any resources attached to it
    void delete(CommandList commandList);

    // the render pass configuration this renderer uses
    RenderPassConfiguration<?> getRenderPassConfiguration();
}
