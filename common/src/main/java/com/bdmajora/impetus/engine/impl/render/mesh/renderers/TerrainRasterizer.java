package com.bdmajora.impetus.engine.impl.render.mesh.renderers;

import com.bdmajora.impetus.engine.impl.gl.shader.ShaderType;
import com.bdmajora.impetus.engine.impl.render.mesh.gl.MeshProgram;
import com.bdmajora.impetus.lwjgl.GLNv;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Phase 3: the actual terrain draw, sourced entirely from a command buffer the GPU wrote
//
// Nothing here knows how many sections there are or where they live; it binds the command buffer by address and
// issues one multi-draw. The task shader picks the quad ranges facing the camera, the mesh shader turns quads
// into triangles.
//
// The commands come from the *previous* frame's section rasteriser, which is why the caller passes last frame's
// region count. That one-frame lag is the price of never reading GPU state back on the CPU
public class TerrainRasterizer {
    // Each command is a uvec2: meshlet count and the section index base
    private static final int COMMAND_BYTES = 8;

    private final MeshProgram program;

    public TerrainRasterizer() {
        this.program = MeshProgram.builder("mesh/terrain")
                .stage(ShaderType.TASK, "impetus:mesh/terrain.task")
                .stage(ShaderType.MESH, "impetus:mesh/terrain.mesh")
                .stage(ShaderType.FRAGMENT, "impetus:mesh/terrain.frag")
                .link();
    }

    public void raster(int regionCount, long commandBufferAddress) {
        this.program.bind();

        LWJGL.glBufferAddressRangeNV(GLNv.GL_DRAW_INDIRECT_ADDRESS_NV, 0, commandBufferAddress,
                (long) regionCount * COMMAND_BYTES);
        // Stride 0 means tightly packed, which is what the section rasteriser writes
        LWJGL.glMultiDrawMeshTasksIndirectNV(0L, regionCount, 0);
    }

    public void delete() {
        this.program.delete();
    }
}
