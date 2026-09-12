package com.bdmajora.impetus.umbra.gl.program;

import com.bdmajora.impetus.umbra.gl.GlResource;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A linked GL program (.vsh/.fsh, optionally geometry or tessellation) owning only the handle and bind/unbind/destroy; uniforms and samplers layer on top, so this serves the engine's own programs too
public class GlProgram extends GlResource {
    // Only for log messages and debugging; GL never sees it
    private final String name;

    // Package-private: ProgramBuilder is the only way to get one, since an unchecked handle is indistinguishable from a working one until the first draw renders nothing
    GlProgram(int handle, String name) {
        this.name = name;
        setHandle(handle);
    }

    // glUseProgram
    public void bind() {
        LWJGL.glUseProgram(getGlId());
    }

    // Binds program 0; deliberately does NOT restore what was bound before, so the caller rebinds if it had something
    public void unbind() {
        LWJGL.glUseProgram(0);
    }

    // -1 when the name is not in the linked program, including uniforms the compiler optimised out; callers treat that as "skip", not an error
    public int getUniformLocation(CharSequence name) {
        return LWJGL.glGetUniformLocation(getGlId(), name);
    }

    // glGetAttribLocation
    public int getAttributeLocation(CharSequence name) {
        return LWJGL.glGetAttribLocation(getGlId(), name);
    }

    // For log lines
    public String getName() {
        return this.name;
    }

    // glDeleteProgram
    @Override
    protected void destroyInternal() {
        LWJGL.glDeleteProgram(getGlId());
    }
}
