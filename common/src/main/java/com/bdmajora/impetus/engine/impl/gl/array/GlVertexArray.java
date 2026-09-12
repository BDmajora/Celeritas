package com.bdmajora.impetus.engine.impl.gl.array;

import com.bdmajora.impetus.engine.impl.gl.GlObject;
import com.bdmajora.impetus.lwjgl.LWJGLServiceProvider;

// A vertex array object, on the drivers that have them
// Wrapped rather than used directly because VAOs reach this codebase through three different entry points — core
// GL 3.0, ARB_vertex_array_object, or nothing at all — and the LWJGL service picks between them
public class GlVertexArray extends GlObject {
    public static final int NULL_ARRAY_ID = 0;

    public GlVertexArray() {
        this.setHandle(LWJGLServiceProvider.LWJGL.glGenVertexArrays());
    }

    // glDeleteVertexArrays
    @Override
    protected void destroyInternal() {
        LWJGLServiceProvider.LWJGL.glDeleteVertexArrays(this.handle());
    }
}
