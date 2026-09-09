package com.bdmajora.impetus.engine.impl.render.chunk;

import com.bdmajora.impetus.engine.impl.gl.device.CommandList;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.ChunkRenderListIterable;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;
import com.bdmajora.impetus.engine.impl.render.viewport.CameraTransform;

// the chunk render backend manages the graphics resource state of chunk render containers, which covers
// uploading their data to the graphics card and the rendering itself
public interface ChunkRenderer {
    // renders the given chunk render list to the active framebuffer
    // matrices are the camera matrices, commandList is where the OpenGL commands are serialized to,
    // renderLists is the collection of render lists, pass is the block render pass to execute,
    // occlusionCamera is the camera context used for block face culling, and camera carries the chunk
    // offsets for the current render
    void render(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists,
                TerrainRenderPass pass, CameraTransform occlusionCamera, CameraTransform camera);

    // deletes this render backend and any resources attached to it
    void delete(CommandList commandList);

    // the render pass configuration this renderer uses
    RenderPassConfiguration<?> getRenderPassConfiguration();
}
