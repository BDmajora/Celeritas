package com.bdmajora.impetus.engine.impl.render.mesh.renderers;

import com.bdmajora.impetus.engine.impl.gl.shader.ShaderType;
import com.bdmajora.impetus.engine.impl.render.mesh.gl.MeshProgram;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Phase 2 of the frame, and the piece that removes the CPU from the loop
//
// One task-shader workgroup per visible region. It skips regions the region rasteriser rejected, dispatches one
// meshlet per live section in the ones it keeps, and — the important part — writes the indirect draw command that
// the terrain rasteriser will consume. The mesh shader draws each section's bounding box and the fragment shader
// records which sections were actually reached
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
