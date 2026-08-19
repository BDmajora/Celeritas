package com.bdmajora.impetus.iris.gl.program;

import com.bdmajora.impetus.iris.gl.GlResource;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * A linked GL program (a {@code .vsh}/{@code .fsh} pair, optionally with geometry/tessellation stages).
 * <p>
 * Build one with {@link ProgramBuilder}. Uniform and sampler binding are layered on top via {@link ProgramUniforms};
 * this class itself only owns the program handle and the bind/unbind/destroy lifecycle.
 */
public class GlProgram extends GlResource {
    private final String name;

    GlProgram(int handle, String name) {
        this.name = name;
        setHandle(handle);
    }

    public void bind() {
        LWJGL.glUseProgram(getGlId());
    }

    public void unbind() {
        LWJGL.glUseProgram(0);
    }

    public int getUniformLocation(CharSequence name) {
        return LWJGL.glGetUniformLocation(getGlId(), name);
    }

    public int getAttributeLocation(CharSequence name) {
        return LWJGL.glGetAttribLocation(getGlId(), name);
    }

    public String getName() {
        return this.name;
    }

    @Override
    protected void destroyInternal() {
        LWJGL.glDeleteProgram(getGlId());
    }
}
