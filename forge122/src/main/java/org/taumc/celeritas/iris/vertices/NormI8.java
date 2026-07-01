package org.taumc.celeritas.iris.vertices;

import org.joml.Vector3f;

/**
 * Packs a normal or tangent vector into a 32-bit int, 8 signed bits per component (range [-1, 1]), XYZ + W layout.
 * Ported from Sodium/Iris (LGPLv3); the {@code Mth.clamp} dependency is inlined so this stays MC-free and reusable.
 * <pre>
 * | 32        | 24        | 16        | 8          |
 * | W         | X         | Y         | Z          |
 * </pre>
 */
public final class NormI8 {
    private static final int X_OFFSET = 0;
    private static final int Y_OFFSET = 8;
    private static final int Z_OFFSET = 16;
    private static final int W_OFFSET = 24;

    private static final float COMPONENT_RANGE = 127.0f;
    private static final float NORM = 1.0f / COMPONENT_RANGE;

    private NormI8() {
    }

    public static int pack(Vector3f normal) {
        return pack(normal.x(), normal.y(), normal.z(), 0);
    }

    public static int pack(float x, float y, float z, float w) {
        return ((int) (x * 127) & 0xFF)
                | (((int) (y * 127) & 0xFF) << 8)
                | (((int) (z * 127) & 0xFF) << 16)
                | (((int) (w * 127) & 0xFF) << 24);
    }

    public static float unpackX(int norm) {
        return ((byte) ((norm >> X_OFFSET) & 0xFF)) * NORM;
    }

    public static float unpackY(int norm) {
        return ((byte) ((norm >> Y_OFFSET) & 0xFF)) * NORM;
    }

    public static float unpackZ(int norm) {
        return ((byte) ((norm >> Z_OFFSET) & 0xFF)) * NORM;
    }

    public static float unpackW(int norm) {
        return ((byte) ((norm >> W_OFFSET) & 0xFF)) * NORM;
    }
}
