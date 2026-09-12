package com.bdmajora.impetus.engine.api.util;

import org.joml.Math;
import org.joml.Vector3f;

// utilities for working with packed normal vectors; each component provides 8 bits of precision in the
// range [-1.0, 1.0]
// | 32        | 24        | 16        | 8          |
// | 0000 0000 | 0110 1100 | 0110 1100 | 0110 1100  |
// | Padding   | X         | Y         | Z          |
public class NormI8 {
    private static final int X_COMPONENT_OFFSET = 0;
    private static final int Y_COMPONENT_OFFSET = 8;
    private static final int Z_COMPONENT_OFFSET = 16;

    // the maximum value of a normal's vector component
    private static final float COMPONENT_RANGE = 127.0f;

    // constant that a floating-point vector component is multiplied by to get the normalized value
    // the multiplication is slightly faster than a floating point division, and this code is a hot path
    // which justifies it
    private static final float NORM = 1.0f / COMPONENT_RANGE;

    // Vector form of pack
    public static int pack(Vector3f normal) {
        return pack(normal.x(), normal.y(), normal.z());
    }

    // packs the vector components into a 32-bit integer in XYZ ordering, with the 8 bits of padding at
    // the end
    public static int pack(float x, float y, float z) {
        int normX = encode(x);
        int normY = encode(y);
        int normZ = encode(z);

        return (normZ << Z_COMPONENT_OFFSET) | (normY << Y_COMPONENT_OFFSET) | (normX << X_COMPONENT_OFFSET);
    }

    // encodes a float in -1.0..1.0 as a normalised unsigned integer in 0..255, ready for graphics memory
    private static int encode(float comp) {
        // TODO: is the clamp necessary here? our inputs should always be normalized vector components
        return ((int) (Math.clamp(-1.0F, 1.0F, comp) * COMPONENT_RANGE) & 255);
    }

    // unpacks the x component of the packed normal, denormalising it to a float in -1.0..1.0
    public static float unpackX(int norm) {
        return ((byte) ((norm >> X_COMPONENT_OFFSET) & 0xFF)) * NORM;
    }

    // unpacks the y component of the packed normal, denormalising it to a float in -1.0..1.0
    public static float unpackY(int norm) {
        return ((byte) ((norm >> Y_COMPONENT_OFFSET) & 0xFF)) * NORM;
    }

    // unpacks the z component of the packed normal, denormalising it to a float in -1.0..1.0
    public static float unpackZ(int norm) {
        return ((byte) ((norm >> Z_COMPONENT_OFFSET) & 0xFF)) * NORM;
    }
}
