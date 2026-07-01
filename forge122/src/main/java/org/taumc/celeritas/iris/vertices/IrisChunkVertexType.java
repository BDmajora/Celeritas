package org.taumc.celeritas.iris.vertices;

import org.embeddedt.embeddium.impl.gl.attribute.GlVertexAttributeFormat;
import org.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;

import java.util.Map;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The terrain vertex format used when a shader pack is active: Embeddium's compact 20-byte layout with the three
 * OptiFine per-vertex attributes appended — {@code mc_midTexCoord} (vec2), {@code at_tangent} (packed vec4), and
 * {@code mc_Entity} (vec2). Position/color/uv/light are byte-identical to {@code CompactChunkVertex} so the same
 * decode prologue works; the extra data comes from the meshing pipeline (see {@link ExtendedDataHelper}/
 * {@link NormalHelper}).
 * <p>
 * This type is only selected while shaders are loaded (Task #6 flips {@code CeleritasWorldRenderer.chooseVertexType}),
 * so the wider stride never costs anything when shaders are off.
 */
public class IrisChunkVertexType implements ChunkVertexType {
    public static final int STRIDE = 40;

    // Offsets after the 20-byte compact base.
    private static final int OFFSET_MID_TEX = 20; // 2 x float
    private static final int OFFSET_TANGENT = 28; // 4 x byte (packed, normalized)
    private static final int OFFSET_ENTITY = 32;  // 2 x float

    public static final GlVertexFormat VERTEX_FORMAT = GlVertexFormat.builder(STRIDE)
            .addElement("a_PosId", 0, GlVertexAttributeFormat.UNSIGNED_SHORT, 4, false, true)
            .addElement("a_Color", 8, GlVertexAttributeFormat.UNSIGNED_BYTE, 4, true, false)
            .addElement("a_TexCoord", 12, GlVertexAttributeFormat.UNSIGNED_SHORT, 2, false, false)
            .addElement("a_LightCoord", 16, GlVertexAttributeFormat.UNSIGNED_SHORT, 2, false, true)
            .addElement("mc_midTexCoord", OFFSET_MID_TEX, GlVertexAttributeFormat.FLOAT, 2, false, false)
            .addElement("at_tangent", OFFSET_TANGENT, GlVertexAttributeFormat.BYTE, 4, true, false)
            .addElement("mc_Entity", OFFSET_ENTITY, GlVertexAttributeFormat.FLOAT, 2, false, false)
            .build();

    // Mirror CompactChunkVertex's position/texture encoding so the decode is shared.
    private static final int POSITION_MAX_VALUE = 65536;
    private static final int TEXTURE_MAX_VALUE = 32768;
    private static final float MODEL_ORIGIN = 8.0f;
    private static final float MODEL_RANGE = 32.0f;
    private static final float MODEL_SCALE = MODEL_RANGE / POSITION_MAX_VALUE;
    private static final float MODEL_SCALE_INV = POSITION_MAX_VALUE / MODEL_RANGE;
    private static final float TEXTURE_SCALE = (1.0f / TEXTURE_MAX_VALUE);

    @Override
    public float getTextureScale() {
        return TEXTURE_SCALE;
    }

    @Override
    public float getPositionScale() {
        return MODEL_SCALE;
    }

    @Override
    public float getPositionOffset() {
        return -MODEL_ORIGIN;
    }

    @Override
    public GlVertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder createEncoder() {
        return (ptr, material, vertex, sectionIndex) -> {
            LWJGL.memPutShort(ptr + 0, encodePosition(vertex.x));
            LWJGL.memPutShort(ptr + 2, encodePosition(vertex.y));
            LWJGL.memPutShort(ptr + 4, encodePosition(vertex.z));

            LWJGL.memPutByte(ptr + 6, (byte) (material.bits() & 0xFF));
            LWJGL.memPutByte(ptr + 7, (byte) (sectionIndex & 0xFF));

            LWJGL.memPutInt(ptr + 8, vertex.color);

            LWJGL.memPutShort(ptr + 12, encodeTexture(vertex.u));
            LWJGL.memPutShort(ptr + 14, encodeTexture(vertex.v));

            LWJGL.memPutInt(ptr + 16, vertex.light);

            // Iris extended data (populated by the meshing pipeline when shaders are active; zero otherwise).
            LWJGL.memPutFloat(ptr + OFFSET_MID_TEX, vertex.midTexU);
            LWJGL.memPutFloat(ptr + OFFSET_MID_TEX + 4, vertex.midTexV);
            LWJGL.memPutInt(ptr + OFFSET_TANGENT, vertex.tangent);
            LWJGL.memPutFloat(ptr + OFFSET_ENTITY, vertex.blockId);
            LWJGL.memPutFloat(ptr + OFFSET_ENTITY + 4, vertex.blockData);

            return ptr + STRIDE;
        };
    }

    @Override
    public Map<String, String> getDefines() {
        Map<String, String> map = ChunkVertexType.super.getDefines();
        map.put("USE_VERTEX_COMPRESSION", "");
        map.put("IRIS_EXTENDED_VERTEX", "");
        return map;
    }

    private static short encodePosition(float value) {
        return (short) ((MODEL_ORIGIN + value) * MODEL_SCALE_INV);
    }

    private static short encodeTexture(float value) {
        return (short) (Math.round(value * TEXTURE_MAX_VALUE) & 0xFFFF);
    }
}
