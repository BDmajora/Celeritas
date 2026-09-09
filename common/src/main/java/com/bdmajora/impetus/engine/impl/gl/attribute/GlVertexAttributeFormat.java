package com.bdmajora.impetus.engine.impl.gl.attribute;

import com.bdmajora.impetus.lwjgl.GL20;


// One vertex attribute data type: its GL type enum plus the size of a single component in bytes
// A record rather than an enum, because the size has to travel with the type — the vertex format maths needs both
// to compute a stride, and looking the size up from the enum at every call site is how strides drift
public record GlVertexAttributeFormat(int typeId, int size) {
    public static final GlVertexAttributeFormat FLOAT = new GlVertexAttributeFormat(GL20.GL_FLOAT, 4);
    public static final GlVertexAttributeFormat SHORT = new GlVertexAttributeFormat(GL20.GL_SHORT, 2);
    public static final GlVertexAttributeFormat UNSIGNED_SHORT = new GlVertexAttributeFormat(GL20.GL_UNSIGNED_SHORT, 2);
    public static final GlVertexAttributeFormat BYTE = new GlVertexAttributeFormat(GL20.GL_BYTE, 1);
    public static final GlVertexAttributeFormat UNSIGNED_BYTE = new GlVertexAttributeFormat(GL20.GL_UNSIGNED_BYTE, 1);
    public static final GlVertexAttributeFormat UNSIGNED_INT = new GlVertexAttributeFormat(GL20.GL_UNSIGNED_INT, 4);
}
