package com.bdmajora.impetus.engine.impl.render.mesh.renderers;

import com.bdmajora.impetus.engine.impl.gl.shader.ShaderType;
import com.bdmajora.impetus.engine.impl.render.mesh.gl.MeshProgram;
import com.bdmajora.impetus.lwjgl.GLNv;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The terrain draw, one multi-draw by address from a command buffer the GPU wrote last frame; the one-frame lag is the price of never reading GPU state back
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

    // The real draw: one glDrawMeshTasksIndirectNV over every visible region
    public void raster(int regionCount, long commandBufferAddress) {
        this.program.bind();

        LWJGL.glBufferAddressRangeNV(GLNv.GL_DRAW_INDIRECT_ADDRESS_NV, 0, commandBufferAddress,
                (long) regionCount * COMMAND_BYTES);
        // Stride 0 means tightly packed, which is what the section rasteriser writes
        LWJGL.glMultiDrawMeshTasksIndirectNV(0L, regionCount, 0);
    }

    // Frees the program
    public void delete() {
        this.program.delete();
    }
}
