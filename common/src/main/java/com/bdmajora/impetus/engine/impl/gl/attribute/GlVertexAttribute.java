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

    // count is the number of COMPONENTS, e.g. 3 for a vec3, not a byte size
    // normalized decides how fixed-point data is read: true rescales it into [0,1] or [-1,1], false hands the raw
    // integer value to the shader — getting it backwards makes a packed normal read as a huge number
    // pointer is the byte offset of the attribute's first component within the vertex, and stride the distance from
    // one vertex to the next
    // intType selects glVertexAttribIPointer over glVertexAttribPointer, which is what keeps an integer attribute
    // integral instead of being silently converted to float
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

    public boolean isNormalized() {
        return this.normalized;
    }

    public boolean isIntType() {
        return this.intType;
    }
}
