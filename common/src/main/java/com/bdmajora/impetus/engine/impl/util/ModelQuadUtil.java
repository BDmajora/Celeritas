package com.bdmajora.impetus.engine.impl.util;

import com.bdmajora.impetus.engine.impl.model.quad.ModelQuadView;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;
import com.bdmajora.impetus.engine.api.util.ColorARGB;
import com.bdmajora.impetus.engine.api.util.NormI8;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.joml.Vector3f;

// Vanilla's baked-quad vertex format, int[8] per vertex: [0-2] position floats, [3] ARGB color, [4-5] block UV floats, [6] light UV shorts, [7] normal bytes + padding
public class ModelQuadUtil {
    // Integer indices for vertex attributes, useful for accessing baked quad data
    public static final int POSITION_INDEX = 0,
            COLOR_INDEX = 3,
            TEXTURE_INDEX = 4,
            LIGHT_INDEX = 6,
            NORMAL_INDEX = 7;

    // Size of vertex format in 4-byte integers
    public static final int VERTEX_SIZE = 8;

    // Index into the flat vertex array for a vertex
    public static int vertexOffset(int vertexIndex) {
        return vertexIndex * VERTEX_SIZE;
    }

    // Closest axis-aligned facing to a normal, or UNASSIGNED when none dominates
    public static ModelQuadFacing findNormalFace(float x, float y, float z) {
        return QuadUtil.findNormalFace(x, y, z);
    }

    // Packed-normal form
    public static ModelQuadFacing findNormalFace(int normal) {
        return findNormalFace(NormI8.unpackX(normal), NormI8.unpackY(normal), NormI8.unpackZ(normal));
    }

    // Face normal from the quad's vertex positions, packed
    public static int calculateNormal(ModelQuadView quad) {
        final float x0 = quad.getX(0);
        final float y0 = quad.getY(0);
        final float z0 = quad.getZ(0);

        final float x1 = quad.getX(1);
        final float y1 = quad.getY(1);
        final float z1 = quad.getZ(1);

        final float x2 = quad.getX(2);
        final float y2 = quad.getY(2);
        final float z2 = quad.getZ(2);

        final float x3 = quad.getX(3);
        final float y3 = quad.getY(3);
        final float z3 = quad.getZ(3);

        final float dx0 = x2 - x0;
        final float dy0 = y2 - y0;
        final float dz0 = z2 - z0;
        final float dx1 = x3 - x1;
        final float dy1 = y3 - y1;
        final float dz1 = z3 - z1;

        float normX = dy0 * dz1 - dz0 * dy1;
        float normY = dz0 * dx1 - dx0 * dz1;
        float normZ = dx0 * dy1 - dy0 * dx1;

        float l = (float) Math.sqrt(normX * normX + normY * normY + normZ * normZ);

        if (l != 0) {
            normX /= l;
            normY /= l;
            normZ /= l;
        }

        return NormI8.pack(normX, normY, normZ);
    }

    // Face normal into a vector, for the encoder path
    public static void calculateNormal(ChunkVertexEncoder.Vertex[] quad, Vector3f result) {
        QuadUtil.calculateNormal(quad, result);
    }

    // Prefers the model's supplied normal, falling back to the computed one
    public static int mergeNormal(int packedNormal, int calcNormal) {
        if((packedNormal & 0xFFFFFF) == 0)
            return calcNormal;
        return packedNormal;
    }

    // Combines baked light with vanilla emission, taking the brighter per channel
    public static int mergeBakedLight(int packedLight, int vanillaLightEmission, int calcLight) {
        // bail early in most cases
        if (packedLight == 0 && vanillaLightEmission == 0)
            return calcLight;

        int psl = (packedLight >> 16) & 0xFF;
        int csl = (calcLight >> 16) & 0xFF;
        int pbl = (packedLight) & 0xFF;
        int cbl = (calcLight) & 0xFF;
        int bl = Math.max(Math.max(pbl, cbl), vanillaLightEmission);
        // Emission raises BLOCK light only, never sky light (folding it into sky reported torches as open to the sky); latent while getVanillaLightEmission() defaults to 0
        int sl = Math.max(psl, csl);
        return (sl << 16) | bl;
    }

    // Mixes two ARGB colors like Forge's VertexConsumer does; bails early for the common single-source case
    public static int mixARGBColors(int colorA, int colorB) {
        // Most common case: Either quad coloring or tint-based coloring, but not both
        if (colorA == -1) {
            return colorB;
        } else if (colorB == -1) {
            return colorA;
        }
        // General case (rare): Both colorings, actually perform the multiplication
        int a = (int)((ColorARGB.unpackAlpha(colorA)/255.0f) * (ColorARGB.unpackAlpha(colorB)/255.0f) * 255.0f);
        int b = (int)((ColorARGB.unpackBlue(colorA)/255.0f) * (ColorARGB.unpackBlue(colorB)/255.0f) * 255.0f);
        int g = (int)((ColorARGB.unpackGreen(colorA)/255.0f) * (ColorARGB.unpackGreen(colorB)/255.0f) * 255.0f);
        int r = (int)((ColorARGB.unpackRed(colorA)/255.0f) * (ColorARGB.unpackRed(colorB)/255.0f) * 255.0f);
        return ColorARGB.pack(r, g, b, a);
    }
}
