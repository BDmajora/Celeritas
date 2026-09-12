package com.bdmajora.impetus.engine.impl.gl.attribute;

import lombok.Getter;

public class GlVertexAttribute {
    @Getter
    private final GlVertexAttributeFormat format;
    @Getter
    private final int count;
    @Getter
    private final int pointer;
    @Getter
    private final int size;
    @Getter
    private final int stride;

    private final boolean normalized;
    private final boolean intType;

    @Getter
    private final String name;

    // count is COMPONENTS (3 for a vec3), normalized rescales fixed-point into [0,1]/[-1,1], pointer is the byte offset in the vertex, intType uses glVertexAttribIPointer so integers stay integral
    public GlVertexAttribute(GlVertexAttributeFormat format, String name, int count, boolean normalized, int pointer, int stride, boolean intType) {
        this(format, format.size() * count, count, name, normalized, pointer, stride, intType);
    }

    protected GlVertexAttribute(GlVertexAttributeFormat format, int size, int count, String name, boolean normalized, int pointer, int stride, boolean intType) {
        this.format = format;
        this.size = size;
        this.count = count;
        this.normalized = normalized;
        this.pointer = pointer;
        this.stride = stride;
        this.intType = intType;
        this.name = name;
    }

    // Whether integer data maps to [0,1] or [-1,1]
    public boolean isNormalized() {
        return this.normalized;
    }

    // Whether to use glVertexAttribIPointer
    public boolean isIntType() {
        return this.intType;
    }
}
