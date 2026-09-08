package com.bdmajora.impetus.umbra.vertices;

import com.bdmajora.impetus.engine.impl.gl.attribute.GlVertexAttributeFormat;
import com.bdmajora.impetus.engine.impl.gl.attribute.GlVertexFormat;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexType;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The terrain vertex format used while a shader pack is active: byte-identical to
 * {@code VanillaLikeChunkVertex} (float position, byte color, float UV, packed light/draw-params — the layout
 * {@code ImpetusTerrainTransformer}'s prologue decodes) with the OptiFine per-vertex attributes appended:
 * the true face normal ({@code gl_Normal}), {@code at_tangent}, {@code mc_midTexCoord} (centre of the quad's
 * texture region in atlas UV — the mean of its four vertex UVs, NOT the sprite centre),
 * and {@code mc_Entity}. The entity attribute follows the shader-facing OptiFine/Umbra shape:
 * {@code (block id, render type, metadata, 1)}. With a pack {@code block.properties}, the block id is the pack's
 * mapped id; without one it is the raw 1.12.2 block id. The extra data comes straight off
 * {@link ChunkVertexEncoder.Vertex}'s Umbra fields, which the meshing pipeline populates when shaders are on.
 * Only selected by {@code ImpetusWorldRenderer.chooseVertexType} while a pack is loaded, so the wider stride
 * costs nothing otherwise.
 */
public class UmbraChunkVertexType implements ChunkVertexType {
    public static final UmbraChunkVertexType INSTANCE = new UmbraChunkVertexType();

    public static final int STRIDE = 56;

    // Offsets after the 28-byte vanilla-like base.
    private static final int OFFSET_NORMAL = 28;   // NormI8-packed face normal (4 normalized signed bytes)
    private static final int OFFSET_TANGENT = 32;  // NormI8-packed tangent, w = handedness
    private static final int OFFSET_MID_TEX = 36;  // 2 x float, quad texture centre in atlas UV space
    private static final int OFFSET_ENTITY = 44;   // 4 x short, mc_Entity.xyzw (see class doc)
    private static final int OFFSET_MID_BLOCK = 52; // at_midBlock: 3 signed bytes (offset * 64) + emission byte

    public static final GlVertexFormat VERTEX_FORMAT = GlVertexFormat.builder(STRIDE)
            .addElement("a_PosId", 0, GlVertexAttributeFormat.FLOAT, 3, false, false)
            .addElement("a_Color", 12, GlVertexAttributeFormat.UNSIGNED_BYTE, 4, true, false)
            .addElement("a_TexCoord", 16, GlVertexAttributeFormat.FLOAT, 2, false, false)
            .addElement("a_LightCoord", 24, GlVertexAttributeFormat.UNSIGNED_INT, 1, false, true)
            .addElement("iris_Normal", OFFSET_NORMAL, GlVertexAttributeFormat.BYTE, 4, true, false)
            .addElement("iris_Tangent", OFFSET_TANGENT, GlVertexAttributeFormat.BYTE, 4, true, false)
            .addElement("iris_MidTexCoord", OFFSET_MID_TEX, GlVertexAttributeFormat.FLOAT, 2, false, false)
            .addElement("iris_BlockInfo", OFFSET_ENTITY, GlVertexAttributeFormat.SHORT, 4, false, false)
            // at_midBlock: raw bytes. xyz are signed *64-scaled offsets, w is block emission.
            .addElement("iris_MidBlock", OFFSET_MID_BLOCK, GlVertexAttributeFormat.BYTE, 4, false, false)
            .build();

    private UmbraChunkVertexType() {
    }

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
            // Base layout identical to VanillaLikeChunkVertex (the transformer prologue decodes this).
            LWJGL.memPutFloat(ptr + 0, vertex.x);
            LWJGL.memPutFloat(ptr + 4, vertex.y);
            LWJGL.memPutFloat(ptr + 8, vertex.z);
            LWJGL.memPutInt(ptr + 12, vertex.color);
            LWJGL.memPutFloat(ptr + 16, encodeTexture(vertex.u));
            LWJGL.memPutFloat(ptr + 20, encodeTexture(vertex.v));
            LWJGL.memPutInt(ptr + 24, (encodeDrawParameters(material.bits(), sectionIndex) << 0) | (encodeLight(vertex.light) << 16));

            // OptiFine extended attributes, filled in by the meshing pipeline while shaders are active.
            LWJGL.memPutInt(ptr + OFFSET_NORMAL, vertex.trueNormal);
            LWJGL.memPutInt(ptr + OFFSET_TANGENT, vertex.tangent);
            LWJGL.memPutFloat(ptr + OFFSET_MID_TEX, vertex.midTexU);
            LWJGL.memPutFloat(ptr + OFFSET_MID_TEX + 4, vertex.midTexV);
            LWJGL.memPutShort(ptr + OFFSET_ENTITY, clampShort(vertex.blockId));
            LWJGL.memPutShort(ptr + OFFSET_ENTITY + 2, clampShort(vertex.blockRenderType));
            LWJGL.memPutShort(ptr + OFFSET_ENTITY + 4, clampShort(vertex.blockData));
            LWJGL.memPutShort(ptr + OFFSET_ENTITY + 6, (short) 1);

            // at_midBlock: offset-to-block-center (block units) * 64 plus block emission in w, like upstream Umbra.
            int mbx = clampByte(Math.round(vertex.midBlockX * 64.0f));
            int mby = clampByte(Math.round(vertex.midBlockY * 64.0f));
            int mbz = clampByte(Math.round(vertex.midBlockZ * 64.0f));
            int mbe = clampUnsignedByte(vertex.blockEmission);
            LWJGL.memPutInt(ptr + OFFSET_MID_BLOCK, (mbx & 0xFF) | ((mby & 0xFF) << 8) | ((mbz & 0xFF) << 16) | ((mbe & 0xFF) << 24));

            return ptr + STRIDE;
        };
    }

    private static int clampByte(int value) {
        return value < -128 ? -128 : (value > 127 ? 127 : value);
    }

    private static int clampUnsignedByte(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private static short clampShort(int value) {
        return (short) (value < Short.MIN_VALUE ? Short.MIN_VALUE : Math.min(value, Short.MAX_VALUE));
    }

    private static int encodeDrawParameters(int materialBits, int sectionIndex) {
        return (((sectionIndex & 0xFF) << 8) | ((materialBits & 0xFF) << 0));
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
