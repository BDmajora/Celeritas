package com.bdmajora.impetus.engine.impl.gl.array;

import com.bdmajora.impetus.engine.impl.gl.GlObject;
import com.bdmajora.impetus.lwjgl.LWJGLServiceProvider;

// VAO wrapper; VAOs arrive via core GL 3.0, ARB_vertex_array_object, or not at all, and the LWJGL service picks between them
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
