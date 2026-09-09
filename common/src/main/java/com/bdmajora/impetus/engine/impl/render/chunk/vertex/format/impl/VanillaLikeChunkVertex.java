package com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.impl;

import com.bdmajora.impetus.engine.impl.gl.attribute.GlVertexAttributeFormat;
import com.bdmajora.impetus.engine.impl.gl.attribute.GlVertexFormat;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.material.Material;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexType;
import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The wide terrain vertex format: slower and heavier on VRAM than CompactChunkVertex, but it keeps full precision
// CompactChunkVertex quantises positions and UVs, which is invisible on vanilla models and visible on modded ones
// that place geometry at fine sub-block offsets — this format exists for those
public class VanillaLikeChunkVertex implements ChunkVertexType {
    public static final int STRIDE = 28;

    public static final GlVertexFormat VERTEX_FORMAT = GlVertexFormat.builder(STRIDE)
            .addElement("a_PosId", 0, GlVertexAttributeFormat.FLOAT, 3, false, false)
            .addElement("a_Color", 12, GlVertexAttributeFormat.UNSIGNED_BYTE, 4, true, false)
            .addElement("a_TexCoord", 16, GlVertexAttributeFormat.FLOAT, 2, false, false)
            .addElement("a_LightCoord", 24, GlVertexAttributeFormat.UNSIGNED_INT, 1, false, true)
            .build();

    @Override
    public float getPositionScale() {
        return 1f;
    }

    @Override
    public float getPositionOffset() {
        return 0;
    }

    @Override
    public float getTextureScale() {
        return 1f;
    }

    @Override
    public GlVertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder createEncoder() {
        return (ptr, material, vertex, sectionIndex) -> {
            LWJGL.memPutFloat(ptr + 0, vertex.x);
            LWJGL.memPutFloat(ptr + 4, vertex.y);
            LWJGL.memPutFloat(ptr + 8, vertex.z);
            LWJGL.memPutInt(ptr + 12, vertex.color);
            LWJGL.memPutFloat(ptr + 16, encodeTexture(vertex.u));
            LWJGL.memPutFloat(ptr + 20, encodeTexture(vertex.v));
            LWJGL.memPutInt(ptr + 24, (encodeDrawParameters(material, sectionIndex) << 0) | (encodeLight(vertex.light) << 16));

            return ptr + STRIDE;
        };
    }

    private static int encodeDrawParameters(Material material, int sectionIndex) {
        return (((sectionIndex & 0xFF) << 8) | ((material.bits() & 0xFF) << 0));
    }

    private static int encodeLight(int light) {
        int block = light & 0xFF;
        int sky = (light >> 16) & 0xFF;
        return ((block << 0) | (sky << 8));
    }

    private static float encodeTexture(float value) {
        return Math.min(0.99999997F, value);
    }
}
