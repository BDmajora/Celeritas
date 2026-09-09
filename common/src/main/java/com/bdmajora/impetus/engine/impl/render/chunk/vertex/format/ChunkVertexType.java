package com.bdmajora.impetus.engine.impl.render.chunk.vertex.format;

import com.bdmajora.impetus.engine.impl.gl.attribute.GlVertexFormat;
import org.jetbrains.annotations.MustBeInvokedByOverriders;

import java.util.HashMap;
import java.util.Map;

public interface ChunkVertexType {
    // the scale applied to vertex coordinates
    float getPositionScale();

    // the translation applied to vertex coordinates
    float getPositionOffset();

    // the scale applied to texture coordinates
    float getTextureScale();

    GlVertexFormat getVertexFormat();

    // a newly constructed vertex encoder for this vertex type
    ChunkVertexEncoder createEncoder();

    @MustBeInvokedByOverriders
    default Map<String, String> getDefines() {
        var defines = new HashMap<String, String>();
        defines.put("VERT_POS_SCALE", String.valueOf(this.getPositionScale()));
        defines.put("VERT_POS_OFFSET", String.valueOf(this.getPositionOffset()));
        defines.put("VERT_TEX_SCALE", String.valueOf(this.getTextureScale()));

        return defines;
    }
}
