package com.bdmajora.impetus.umbra.gl.shader;

import com.bdmajora.impetus.lwjgl.GL20;
import com.bdmajora.impetus.lwjgl.GL32;
import com.bdmajora.impetus.lwjgl.GL40;
import com.bdmajora.impetus.lwjgl.GL43;

// GLSL pipeline stages an OptiFine-style 1.12.2 pack can supply — vertex/fragment mandatory, geometry
// needs GL3.2 and tessellation needs GL4.0 (most 1.12.2 packs don't use either)
public enum ShaderType {
    VERTEX(GL20.GL_VERTEX_SHADER),
    GEOMETRY(GL32.GL_GEOMETRY_SHADER),
    TESS_CONTROL(GL40.GL_TESS_CONTROL_SHADER),
    TESS_EVALUATION(GL40.GL_TESS_EVALUATION_SHADER),
    FRAGMENT(GL20.GL_FRAGMENT_SHADER),
    COMPUTE(GL43.GL_COMPUTE_SHADER);

    public final int id;

    ShaderType(int id) {
        this.id = id;
    }
}
