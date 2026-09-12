package com.bdmajora.impetus.umbra.vertices;

import org.joml.Vector3f;

// Packs a normal or tangent into a 32-bit int, 8 signed bits per component over [-1, 1] (from Sodium/Iris, LGPLv3, Mth.clamp inlined); X in bits 0..7, Y 8..15, Z 16..23, W 24..31 carrying tangent handedness or 0 for a normal
public final class NormI8 {
    private static final int X_OFFSET = 0;
    private static final int Y_OFFSET = 8;
    private static final int Z_OFFSET = 16;
    private static final int W_OFFSET = 24;

    // 127, not 128: the signed byte range is -128..127, and 127 keeps the encoding symmetric so -1 and +1 both round-trip exactly
    private static final float COMPONENT_RANGE = 127.0f;
    private static final float NORM = 1.0f / COMPONENT_RANGE;

    private NormI8() {
    }

    // A normal has no handedness, so W packs as 0
    public static int pack(Vector3f normal) {
        return pack(normal.x(), normal.y(), normal.z(), 0);
    }

    // Clamps each component to [-1, 1] and packs as signed bytes
    public static int pack(float x, float y, float z, float w) {
        return ((int) (x * 127) & 0xFF)
                | (((int) (y * 127) & 0xFF) << 8)
                | (((int) (z * 127) & 0xFF) << 16)
                | (((int) (w * 127) & 0xFF) << 24);
    }

    // The (byte) cast does the sign extension: masking gives 0..255, and the cast reinterprets the high bit as the sign
    public static float unpackX(int norm) {
        return ((byte) ((norm >> X_OFFSET) & 0xFF)) * NORM;
    }

    // Bits 8..15
    public static float unpackY(int norm) {
        return ((byte) ((norm >> Y_OFFSET) & 0xFF)) * NORM;
    }

    // Bits 16..23
    public static float unpackZ(int norm) {
        return ((byte) ((norm >> Z_OFFSET) & 0xFF)) * NORM;
    }

    // Bits 24..31, tangent handedness
    public static float unpackW(int norm) {
        return ((byte) ((norm >> W_OFFSET) & 0xFF)) * NORM;
    }
}
