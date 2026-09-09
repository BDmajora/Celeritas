package com.bdmajora.impetus.umbra.gl.program;

import com.bdmajora.impetus.umbra.gl.GlResource;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A linked GL program: a .vsh/.fsh pair, optionally with geometry or tessellation stages
// Owns nothing but the handle and the bind/unbind/destroy lifecycle. Uniforms and samplers are layered on top by
// ProgramUniforms and ProgramSamplers, so this stays usable for the engine's own programs as well as pack ones
public class GlProgram extends GlResource {
    // Only for log messages and debugging; GL never sees it
    private final String name;

    // Package-private: ProgramBuilder is the only legitimate way to get one, because a handle that has not been
    // link-checked is indistinguishable from a working one until the first draw silently renders nothing
    GlProgram(int handle, String name) {
        this.name = name;
        setHandle(handle);
    }

    public void bind() {
        LWJGL.glUseProgram(getGlId());
    }

    // Binds program 0, which is "no program" — this deliberately does NOT restore whatever was bound before, so
    // the caller is responsible for rebinding if it had something
    public void unbind() {
        LWJGL.glUseProgram(0);
    }

    // -1 when the name does not exist in the linked program, which includes uniforms the GLSL compiler optimised
    // out for being unused. Callers treat that as "skip this uniform" rather than as an error
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
