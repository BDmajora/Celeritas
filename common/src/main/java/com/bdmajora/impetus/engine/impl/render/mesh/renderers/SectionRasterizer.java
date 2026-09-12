package com.bdmajora.impetus.engine.impl.render.mesh.renderers;

import com.bdmajora.impetus.engine.impl.gl.shader.ShaderType;
import com.bdmajora.impetus.engine.impl.render.mesh.gl.MeshProgram;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Phase 2, the piece that removes the CPU from the loop: one task workgroup per visible region skips rejected regions, dispatches one meshlet per live section, and writes the indirect draw command the terrain rasteriser consumes
public class SectionRasterizer {
    private final MeshProgram program;

    public SectionRasterizer() {
        this.program = MeshProgram.builder("mesh/section_raster")
                .stage(ShaderType.TASK, "impetus:mesh/section_raster.task")
                .stage(ShaderType.MESH, "impetus:mesh/section_raster.mesh")
                .stage(ShaderType.FRAGMENT, "impetus:mesh/section_raster.frag")
                .link();
    }

    // Debug draw of section bounds
    public void raster(int visibleRegionCount) {
        this.program.bind();
        LWJGL.glDrawMeshTasksNV(0, visibleRegionCount);
    }

    // Frees the program
    public void delete() {
        this.program.delete();
    }
}
