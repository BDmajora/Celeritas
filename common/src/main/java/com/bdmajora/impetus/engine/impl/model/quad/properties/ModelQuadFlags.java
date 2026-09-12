package com.bdmajora.impetus.engine.impl.model.quad.properties;

import com.bdmajora.impetus.engine.impl.common.util.MathUtil;
import com.bdmajora.impetus.engine.impl.model.quad.BakedQuadView;
import com.bdmajora.impetus.engine.impl.model.quad.ModelQuadView;
import com.bdmajora.impetus.engine.api.util.ColorABGR;

public class ModelQuadFlags {
    // the quad does not fully cover the given face for the model
    public static final int IS_PARTIAL = 0b001;

    // the quad is parallel to its light face
    public static final int IS_PARALLEL = 0b010;

    // the quad is aligned to the block grid; only set when IS_PARALLEL is also set
    public static final int IS_ALIGNED = 0b100;

    // The quad should be shaded using vanilla's getShade logic and the light face rather than per-vertex normals
    public static final int IS_VANILLA_SHADED = 0b1000;
    // the particle sprite on this quad can be trusted to be the only sprite it shows
    public static final int IS_TRUSTED_SPRITE = (1 << 4);
    // this quad can use a more optimal terrain render pass based on its sprite
    public static final int IS_PASS_OPTIMIZABLE = (1 << 5);
    // the flags are populated for the quad
    public static final int IS_POPULATED = (1 << 31);

    // true if the bit-flag set contains the given flag
    public static boolean contains(int flags, int mask) {
        return (flags & mask) != 0;
    }

    // Classifies a quad as aligned, partial or parallel to its face, from its vertex positions
    public static int getQuadFlags(ModelQuadView quad, ModelQuadFacing face) {
        return getQuadFlags(quad, face, 0);
    }

    // Checks the quad's vertex order is Minecraft's canonical CCW-from-outside baked order for the face, which AO, lighting interpolation and culling all assume; compares against expected coordinates without allocating
    private static boolean canonicalVertexOrder(ModelQuadView quad, ModelQuadFacing face, float minX, float minY, float minZ,
                                                float maxX, float maxY, float maxZ) {
        return switch (face) {
            case NEG_Y -> MathUtil.roughlyEqual(quad.getX(0), minX) && MathUtil.roughlyEqual(quad.getY(0), minY) && MathUtil.roughlyEqual(quad.getZ(0), maxZ)
                    && MathUtil.roughlyEqual(quad.getX(1), minX) && MathUtil.roughlyEqual(quad.getY(1), minY) && MathUtil.roughlyEqual(quad.getZ(1), minZ)
                    && MathUtil.roughlyEqual(quad.getX(2), maxX) && MathUtil.roughlyEqual(quad.getY(2), minY) && MathUtil.roughlyEqual(quad.getZ(2), minZ)
                    && MathUtil.roughlyEqual(quad.getX(3), maxX) && MathUtil.roughlyEqual(quad.getY(3), minY) && MathUtil.roughlyEqual(quad.getZ(3), maxZ);
            case POS_Y -> MathUtil.roughlyEqual(quad.getX(0), minX) && MathUtil.roughlyEqual(quad.getY(0), maxY) && MathUtil.roughlyEqual(quad.getZ(0), minZ)
                    && MathUtil.roughlyEqual(quad.getX(1), minX) && MathUtil.roughlyEqual(quad.getY(1), maxY) && MathUtil.roughlyEqual(quad.getZ(1), maxZ)
                    && MathUtil.roughlyEqual(quad.getX(2), maxX) && MathUtil.roughlyEqual(quad.getY(2), maxY) && MathUtil.roughlyEqual(quad.getZ(2), maxZ)
                    && MathUtil.roughlyEqual(quad.getX(3), maxX) && MathUtil.roughlyEqual(quad.getY(3), maxY) && MathUtil.roughlyEqual(quad.getZ(3), minZ);
            case NEG_Z -> MathUtil.roughlyEqual(quad.getX(0), maxX) && MathUtil.roughlyEqual(quad.getY(0), maxY) && MathUtil.roughlyEqual(quad.getZ(0), minZ)
                    && MathUtil.roughlyEqual(quad.getX(1), maxX) && MathUtil.roughlyEqual(quad.getY(1), minY) && MathUtil.roughlyEqual(quad.getZ(1), minZ)
                    && MathUtil.roughlyEqual(quad.getX(2), minX) && MathUtil.roughlyEqual(quad.getY(2), minY) && MathUtil.roughlyEqual(quad.getZ(2), minZ)
                    && MathUtil.roughlyEqual(quad.getX(3), minX) && MathUtil.roughlyEqual(quad.getY(3), maxY) && MathUtil.roughlyEqual(quad.getZ(3), minZ);
            case POS_Z -> MathUtil.roughlyEqual(quad.getX(0), minX) && MathUtil.roughlyEqual(quad.getY(0), maxY) && MathUtil.roughlyEqual(quad.getZ(0), maxZ)
                    && MathUtil.roughlyEqual(quad.getX(1), minX) && MathUtil.roughlyEqual(quad.getY(1), minY) && MathUtil.roughlyEqual(quad.getZ(1), maxZ)
                    && MathUtil.roughlyEqual(quad.getX(2), maxX) && MathUtil.roughlyEqual(quad.getY(2), minY) && MathUtil.roughlyEqual(quad.getZ(2), maxZ)
                    && MathUtil.roughlyEqual(quad.getX(3), maxX) && MathUtil.roughlyEqual(quad.getY(3), maxY) && MathUtil.roughlyEqual(quad.getZ(3), maxZ);
            case NEG_X -> MathUtil.roughlyEqual(quad.getX(0), minX) && MathUtil.roughlyEqual(quad.getY(0), maxY) && MathUtil.roughlyEqual(quad.getZ(0), minZ)
                    && MathUtil.roughlyEqual(quad.getX(1), minX) && MathUtil.roughlyEqual(quad.getY(1), minY) && MathUtil.roughlyEqual(quad.getZ(1), minZ)
                    && MathUtil.roughlyEqual(quad.getX(2), minX) && MathUtil.roughlyEqual(quad.getY(2), minY) && MathUtil.roughlyEqual(quad.getZ(2), maxZ)
                    && MathUtil.roughlyEqual(quad.getX(3), minX) && MathUtil.roughlyEqual(quad.getY(3), maxY) && MathUtil.roughlyEqual(quad.getZ(3), maxZ);
            case POS_X -> MathUtil.roughlyEqual(quad.getX(0), maxX) && MathUtil.roughlyEqual(quad.getY(0), maxY) && MathUtil.roughlyEqual(quad.getZ(0), maxZ)
                    && MathUtil.roughlyEqual(quad.getX(1), maxX) && MathUtil.roughlyEqual(quad.getY(1), minY) && MathUtil.roughlyEqual(quad.getZ(1), maxZ)
                    && MathUtil.roughlyEqual(quad.getX(2), maxX) && MathUtil.roughlyEqual(quad.getY(2), minY) && MathUtil.roughlyEqual(quad.getZ(2), minZ)
                    && MathUtil.roughlyEqual(quad.getX(3), maxX) && MathUtil.roughlyEqual(quad.getY(3), maxY) && MathUtil.roughlyEqual(quad.getZ(3), minZ);
            case UNASSIGNED -> false;
        };
    }

    // Calculates the quad's properties, used later by the light pipeline for certain optimizations
    public static int getQuadFlags(ModelQuadView quad, ModelQuadFacing face, int existingFlags) {
        float minX = 32.0F;
        float minY = 32.0F;
        float minZ = 32.0F;

        float maxX = -32.0F;
        float maxY = -32.0F;
        float maxZ = -32.0F;

        int numVertices = 4;
        if (quad instanceof BakedQuadView bakedQuad) {
            numVertices = Math.min(numVertices, bakedQuad.getVerticesCount());
        }

        boolean degenerate = false, nonOpaqueColor = false;

        for (int i = 0; i < numVertices; ++i) {
            float x = quad.getX(i);
            float y = quad.getY(i);
            float z = quad.getZ(i);

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);

            for (int j = 0; j < i; ++j) {
                float px = quad.getX(j);
                float py = quad.getY(j);
                float pz = quad.getZ(j);

                if (MathUtil.roughlyEqual(px, x) &&
                        MathUtil.roughlyEqual(py, y) &&
                        MathUtil.roughlyEqual(pz, z)) {
                    degenerate = true;
                    break;
                }
            }

            if(ColorABGR.unpackAlpha(quad.getColor(i)) != 255) {
                nonOpaqueColor = true;
            }
        }

        // Only set the partial flag when the vertices are in the expected order, since the optimization maps them directly onto corners
        boolean partial = degenerate || (switch (face.getAxis()) {
            case X -> minY >= 0.0001f || minZ >= 0.0001f || maxY <= 0.9999F || maxZ <= 0.9999F;
            case Y -> minX >= 0.0001f || minZ >= 0.0001f || maxX <= 0.9999F || maxZ <= 0.9999F;
            case Z -> minX >= 0.0001f || minY >= 0.0001f || maxX <= 0.9999F || maxY <= 0.9999F;
        }) || !canonicalVertexOrder(quad, face, minX, minY, minZ, maxX, maxY, maxZ);

        boolean parallel = switch(face.getAxis()) {
            case X -> minX == maxX;
            case Y -> minY == maxY;
            case Z -> minZ == maxZ;
        };

        boolean aligned = parallel && switch (face) {
            case NEG_Y -> minY < 0.0001f;
            case POS_Y -> maxY > 0.9999F;
            case NEG_Z -> minZ < 0.0001f;
            case POS_Z -> maxZ > 0.9999F;
            case NEG_X -> minX < 0.0001f;
            case POS_X -> maxX > 0.9999F;
            case UNASSIGNED -> throw new IllegalArgumentException();
        };

        int flags = existingFlags & ~(IS_PARTIAL | IS_PARALLEL | IS_ALIGNED);

        if (partial) {
            flags |= IS_PARTIAL;
        }

        if (parallel) {
            flags |= IS_PARALLEL;
        }

        if (aligned) {
            flags |= IS_ALIGNED;
        }

        if (!nonOpaqueColor && (flags & IS_TRUSTED_SPRITE) != 0) {
            flags |= IS_PASS_OPTIMIZABLE;
        }

        flags |= IS_POPULATED;

        return flags;
    }
}