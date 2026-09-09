package com.bdmajora.impetus.engine.impl.gl.shader;

import com.bdmajora.impetus.lwjgl.GL20;
import com.bdmajora.impetus.lwjgl.GL32;
import com.bdmajora.impetus.lwjgl.GL42;
import com.bdmajora.impetus.lwjgl.GL43;
import com.bdmajora.impetus.lwjgl.GLNv;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;

// The shader stages this engine compiles, each carrying its GL type enum
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum ShaderType {
    VERTEX(GL20.GL_VERTEX_SHADER, "vsh"),
    FRAGMENT(GL20.GL_FRAGMENT_SHADER, "fsh"),
    GEOM(GL32.GL_GEOMETRY_SHADER, "gsh"),
    TESS_CTRL(GL42.GL_TESS_CONTROL_SHADER, "tcs"),
    TESS_EVALUATE(GL42.GL_TESS_EVALUATION_SHADER, "tes"),
    COMPUTE(GL43.GL_COMPUTE_SHADER, "csh"),
    // NV_mesh_shader stages, used only by the mesh-shader terrain backend
    // A driver without the extension rejects these enums at glCreateShader, which is why nothing constructs one
    // without clearing MeshShaderSupport first
    TASK(GLNv.GL_TASK_SHADER_NV, "task"),
    MESH(GLNv.GL_MESH_SHADER_NV, "mesh");

    @Deprecated
    public static final ShaderType TESSELATION_CONTROL = ShaderType.TESS_CTRL;
    @Deprecated
    public static final ShaderType TESSELATION_EVAL = ShaderType.TESS_EVALUATE;
    @Deprecated
    public static final ShaderType GEOMETRY = ShaderType.GEOM;

    public final int id;
    public final String fileExtension;
}
