package org.taumc.celeritas.iris.gl.shader;

import org.taumc.celeritas.lwjgl.GL20;
import org.taumc.celeritas.lwjgl.GL32;
import org.taumc.celeritas.lwjgl.GL40;
import org.taumc.celeritas.lwjgl.GL43;

/**
 * The GLSL pipeline stages an OptiFine-style 1.12.2 shader pack can supply. Vertex and fragment are mandatory for a
 * usable program; geometry and tessellation are optional and require GL3.2 / GL4.0 respectively (most 1.12.2 packs do
 * not use them).
 */
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
