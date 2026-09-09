package com.bdmajora.impetus.umbra.vertices;

import org.joml.Vector3f;

// Pure geometry for the extended vertex data: face normals and tangents
// The tangent routine is a semantics-preserving port from Sodium/Iris (LGPLv3). It derives the tangent basis from a
// triangle's positions and UVs — the standard at_tangent construction OptiFine-style normal mapping needs — and
// packs it through NormI8 with the handedness sign in w
// No Minecraft dependencies at all, which is what makes it safe to call from the chunk-build worker threads
public final class NormalHelper {
    private NormalHelper() {
    }

    // The quad's real face normal from its four corners, written into saveTo rather than returned so the meshing
    // hot path can reuse one vector instead of allocating per quad
    public static void computeFaceNormal(Vector3f saveTo,
                                         float x0, float y0, float z0,
                                         float x2, float y2, float z2,
                                         float x1, float y1, float z1,
                                         float x3, float y3, float z3) {
        // Newell-style diagonal cross product, robust for non-planar quads.
        float dx0 = x2 - x0, dy0 = y2 - y0, dz0 = z2 - z0;
        float dx1 = x3 - x1, dy1 = y3 - y1, dz1 = z3 - z1;

        float nx = dy0 * dz1 - dz0 * dy1;
        float ny = dz0 * dx1 - dx0 * dz1;
        float nz = dx0 * dy1 - dy0 * dx1;

        float scale = rsqrt(nx * nx + ny * ny + nz * nz);
        saveTo.set(nx * scale, ny * scale, nz * scale);
    }

    // Computes and packs the tangent for a triangle from its positions, UVs and face normal
    // The packed w component carries bitangent handedness as +1 or -1, which the shader needs to reconstruct the
    // third basis vector without a second attribute — a wrong sign flips normal-mapped lighting inside out
    public static int computeTangent(float normalX, float normalY, float normalZ,
                                     float x0, float y0, float z0, float u0, float v0,
                                     float x1, float y1, float z1, float u1, float v1,
                                     float x2, float y2, float z2, float u2, float v2) {
        float edge1x = x1 - x0;
        float edge1y = y1 - y0;
        float edge1z = z1 - z0;

        float edge2x = x2 - x0;
        float edge2y = y2 - y0;
        float edge2z = z2 - z0;

        float deltaU1 = u1 - u0;
        float deltaV1 = v1 - v0;
        float deltaU2 = u2 - u0;
        float deltaV2 = v2 - v0;

        float fdenom = deltaU1 * deltaV2 - deltaU2 * deltaV1;
        float f = fdenom == 0.0f ? 1.0f : 1.0f / fdenom;

        float tangentx = f * (deltaV2 * edge1x - deltaV1 * edge2x);
        float tangenty = f * (deltaV2 * edge1y - deltaV1 * edge2y);
        float tangentz = f * (deltaV2 * edge1z - deltaV1 * edge2z);
        float tcoeff = rsqrt(tangentx * tangentx + tangenty * tangenty + tangentz * tangentz);
        tangentx *= tcoeff;
        tangenty *= tcoeff;
        tangentz *= tcoeff;

        float bitangentx = f * (-deltaU2 * edge1x + deltaU1 * edge2x);
        float bitangenty = f * (-deltaU2 * edge1y + deltaU1 * edge2y);
        float bitangentz = f * (-deltaU2 * edge1z + deltaU1 * edge2z);
        float bitcoeff = rsqrt(bitangentx * bitangentx + bitangenty * bitangenty + bitangentz * bitangentz);
        bitangentx *= bitcoeff;
        bitangenty *= bitcoeff;
        bitangentz *= bitcoeff;

        // predicted bitangent = tangent × normal
        float pbitangentx = tangenty * normalZ - tangentz * normalY;
        float pbitangenty = tangentz * normalX - tangentx * normalZ;
        float pbitangentz = tangentx * normalY - tangenty * normalX;

        float dot = (bitangentx * pbitangentx) + (bitangenty * pbitangenty) + (bitangentz * pbitangentz);
        float tangentW = dot < 0 ? -1.0f : 1.0f;

        return NormI8.pack(tangentx, tangenty, tangentz, tangentW);
    }

    private static float rsqrt(float value) {
        if (value == 0.0f) {
            return 1.0f;
        }
        return (float) (1.0 / Math.sqrt(value));
    }
}
