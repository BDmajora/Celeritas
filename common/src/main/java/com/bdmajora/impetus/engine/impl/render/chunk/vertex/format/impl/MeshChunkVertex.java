package com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.impl;

import com.bdmajora.impetus.engine.api.util.ColorABGR;
import com.bdmajora.impetus.engine.api.util.ColorU8;
import com.bdmajora.impetus.engine.impl.gl.attribute.GlVertexFormat;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexType;

import java.util.Map;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The 16-byte terrain vertex the mesh-shader backend reads
//
// Four bytes narrower than CompactChunkVertex, and the saving is the point: the mesh shader fetches four of these
// per quad through a raw pointer, so the format has to be a uvec4 the shader can load in one go. Getting there
// costs two things CompactChunkVertex does not pay:
//   - shade is folded into the colour on the CPU, so alpha is free and RGB packs into 24 bits
//   - light drops from two 16-bit coordinates to two bytes, clamped away from the atlas edges
//
// There is no GlVertexFormat worth declaring, because nothing binds this as vertex attributes; the field exists
// only because ChunkVertexType demands one, and it describes the stride and nothing else
public class MeshChunkVertex implements ChunkVertexType {
    public static final int STRIDE = 16;

    public static final GlVertexFormat VERTEX_FORMAT = GlVertexFormat.builder(STRIDE).build();

    private static final int POSITION_MAX_VALUE = 65536;
    private static final int TEXTURE_MAX_VALUE = 32768;

    private static final float MODEL_ORIGIN = 8.0f;
    private static final float MODEL_RANGE = 32.0f;
    private static final float MODEL_SCALE = MODEL_RANGE / POSITION_MAX_VALUE;
    private static final float MODEL_SCALE_INV = POSITION_MAX_VALUE / MODEL_RANGE;

    private static final float TEXTURE_SCALE = 1.0f / TEXTURE_MAX_VALUE;

    // Packed units per block, i.e. 65536 / 32
    private static final int UNITS_PER_BLOCK = (int) (MODEL_SCALE_INV);
    // Where a section-local coordinate of 0 lands in packed units
    private static final int PACKED_ORIGIN = (int) (MODEL_ORIGIN * MODEL_SCALE_INV);

    public static final MeshChunkVertex INSTANCE = new MeshChunkVertex();

    @Override
    public float getPositionScale() {
        return MODEL_SCALE;
    }

    @Override
    public float getPositionOffset() {
        return -MODEL_ORIGIN;
    }

    @Override
    public float getTextureScale() {
        return TEXTURE_SCALE;
    }

    @Override
    public GlVertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder createEncoder() {
        return (ptr, material, vertex, sectionIndex) -> {
            int light = packLight(vertex.light);

            LWJGL.memPutInt(ptr + 0, encodePosition(vertex.x) | (encodePosition(vertex.y) << 16));
            LWJGL.memPutInt(ptr + 4, encodePosition(vertex.z) | ((material.bits() & 0xFF) << 16) | ((light & 0xFF) << 24));
            LWJGL.memPutInt(ptr + 8, packShadedColor(vertex.color) | (((light >> 8) & 0xFF) << 24));
            LWJGL.memPutInt(ptr + 12, encodeTexture(vertex.u) | (encodeTexture(vertex.v) << 16));

            return ptr + STRIDE;
        };
    }

    @Override
    public Map<String, String> getDefines() {
        var map = ChunkVertexType.super.getDefines();
        map.put("USE_VERTEX_COMPRESSION", "");
        map.put("TEXTURE_MAX_SCALE", String.valueOf(TEXTURE_MAX_VALUE));
        return map;
    }

    // ---- readers, for the section bounding box ----

    public static int readPackedX(long ptr) {
        return LWJGL.memGetInt(ptr) & 0xFFFF;
    }

    public static int readPackedY(long ptr) {
        return (LWJGL.memGetInt(ptr) >>> 16) & 0xFFFF;
    }

    public static int readPackedZ(long ptr) {
        return LWJGL.memGetInt(ptr + 4) & 0xFFFF;
    }

    // Packed position back to which 1-block cell of the section it falls in, clamped to 0..15
    // Vertices legitimately sit slightly outside the section (a fence post model, say), and the occlusion box is
    // per-section, so anything outside is folded onto the boundary cell
    public static int decodeBlockCoord(int packed) {
        int block = (packed - PACKED_ORIGIN) / UNITS_PER_BLOCK;
        return Math.max(0, Math.min(15, block));
    }

    public static float decodePosition(int packed) {
        return (packed / MODEL_SCALE_INV) - MODEL_ORIGIN;
    }

    private static int encodePosition(float value) {
        return ((int) ((MODEL_ORIGIN + value) * MODEL_SCALE_INV)) & 0xFFFF;
    }

    private static int encodeTexture(float value) {
        return Math.round(value * TEXTURE_MAX_VALUE) & 0xFFFF;
    }

    // Folds the shade factor (which the mesher stashes in alpha) into RGB, so the shader never has to read it
    // and the fourth byte is free for the sky light level
    private static int packShadedColor(int color) {
        float shade = ColorU8.byteToNormalizedFloat(ColorABGR.unpackAlpha(color));

        int r = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackRed(color)) * shade);
        int g = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackGreen(color)) * shade);
        int b = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackBlue(color)) * shade);

        return ColorABGR.pack(r, g, b, 0x00);
    }

    // 16-bit lightmap coordinates down to one byte each
    // Clamped to 8..248 for the same reason the lightmap is sampled with CLAMP_TO_EDGE: the outermost texels of
    // the lightmap are not meant to be reached, and rounding to a byte would otherwise land on them
    private static int packLight(int light) {
        int sky = clamp((light >>> 16) & 0xFF, 8, 248);
        int block = clamp(light & 0xFF, 8, 248);

        return block | (sky << 8);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
