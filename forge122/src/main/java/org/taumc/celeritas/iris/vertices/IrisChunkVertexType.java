package org.taumc.celeritas.iris.vertices;

import org.embeddedt.embeddium.impl.gl.attribute.GlVertexAttributeFormat;
import org.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The terrain vertex format used while a shader pack is active: byte-identical to
 * {@code VanillaLikeChunkVertex} (float position, byte color, float UV, packed light/draw-params — the layout
 * {@code EmbeddiumTerrainTransformer}'s prologue decodes) with the OptiFine per-vertex attributes appended:
 * the true face normal ({@code gl_Normal}), {@code at_tangent}, {@code mc_midTexCoord} (sprite center in atlas UV),
 * and {@code mc_Entity} (block id + metadata). The extra data comes straight off
 * {@link ChunkVertexEncoder.Vertex}'s Iris fields, which the meshing pipeline populates when shaders are on.
 * Only selected by {@code CeleritasWorldRenderer.chooseVertexType} while a pack is loaded, so the wider stride
 * costs nothing otherwise.
 */
public class IrisChunkVertexType implements ChunkVertexType {
    public static final IrisChunkVertexType INSTANCE = new IrisChunkVertexType();

    public static final int STRIDE = 52;

    // Offsets after the 28-byte vanilla-like base.
    private static final int OFFSET_NORMAL = 28;   // NormI8-packed face normal (4 normalized signed bytes)
    private static final int OFFSET_TANGENT = 32;  // NormI8-packed tangent, w = handedness
    private static final int OFFSET_MID_TEX = 36;  // 2 x float, sprite center in atlas UV space
    private static final int OFFSET_ENTITY = 44;   // 2 x float, (block id, metadata)

    public static final GlVertexFormat VERTEX_FORMAT = GlVertexFormat.builder(STRIDE)
            .addElement("a_PosId", 0, GlVertexAttributeFormat.FLOAT, 3, false, false)
            .addElement("a_Color", 12, GlVertexAttributeFormat.UNSIGNED_BYTE, 4, true, false)
            .addElement("a_TexCoord", 16, GlVertexAttributeFormat.FLOAT, 2, false, false)
            .addElement("a_LightCoord", 24, GlVertexAttributeFormat.UNSIGNED_INT, 1, false, true)
            .addElement("iris_Normal", OFFSET_NORMAL, GlVertexAttributeFormat.BYTE, 4, true, false)
            .addElement("iris_Tangent", OFFSET_TANGENT, GlVertexAttributeFormat.BYTE, 4, true, false)
            .addElement("iris_MidTexCoord", OFFSET_MID_TEX, GlVertexAttributeFormat.FLOAT, 2, false, false)
            .addElement("iris_BlockInfo", OFFSET_ENTITY, GlVertexAttributeFormat.FLOAT, 2, false, false)
            .build();

    private IrisChunkVertexType() {
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
            LWJGL.memPutFloat(ptr + OFFSET_ENTITY, vertex.blockId);
            LWJGL.memPutFloat(ptr + OFFSET_ENTITY + 4, vertex.blockData);

            return ptr + STRIDE;
        };
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
