package com.bdmajora.impetus.engine.impl.render.mesh.renderers;

import com.bdmajora.impetus.engine.impl.gl.shader.ShaderType;
import com.bdmajora.impetus.engine.impl.render.mesh.gl.MeshProgram;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Phase 1 of the frame: draw one box per frustum-visible region and record which ones survive the depth test
//
// No task shader, because the workload is fixed — one meshlet per region, eight vertices and twelve triangles.
// The fragment shader writes a byte, nothing else; colour and depth writes are off and the representative
// fragment test cuts shading to one fragment per primitive
public class RegionRasterizer {
    private final MeshProgram program;

    public RegionRasterizer() {
        this.program = MeshProgram.builder("mesh/region_raster")
                .stage(ShaderType.MESH, "impetus:mesh/region_raster.mesh")
                .stage(ShaderType.FRAGMENT, "impetus:mesh/region_raster.frag")
                .link();
    }

    // Debug draw of region bounds
    public void raster(int visibleRegionCount) {
        this.program.bind();
        LWJGL.glDrawMeshTasksNV(0, visibleRegionCount);
    }

    // Frees the program
    public void delete() {
        this.program.delete();
    }
}
