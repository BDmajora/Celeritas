package com.bdmajora.impetus.engine.impl.gl.array;

import com.bdmajora.impetus.engine.impl.gl.GlObject;
import com.bdmajora.impetus.lwjgl.LWJGLServiceProvider;

/**
 * Provides Vertex Array functionality on supported platforms.
 */
public class GlVertexArray extends GlObject {
    public static final int NULL_ARRAY_ID = 0;

    public GlVertexArray() {
        this.setHandle(LWJGLServiceProvider.LWJGL.glGenVertexArrays());
    }

    @Override
    protected void destroyInternal() {
        LWJGLServiceProvider.LWJGL.glDeleteVertexArrays(this.handle());
    }
}
