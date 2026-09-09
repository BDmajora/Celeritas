package com.bdmajora.impetus.engine.api.util;

public interface ColorU8 {
    // the number of bits used for each colour component
    int COMPONENT_BITS = 8;

    // the bitwise mask for each colour component
    int COMPONENT_MASK = (1 << COMPONENT_BITS) - 1;

    // the maximum value of a colour component, for converting normalised floats to integers
    float COMPONENT_RANGE = (float) COMPONENT_MASK;

    // the multiplicative inverse of COMPONENT_RANGE, for converting integers to normalised floats
    float COMPONENT_RANGE_INVERSE = 1.0f / COMPONENT_RANGE;

    // converts a normalised float in 0.0..1.0 to an integer component in 0..255
    static int normalizedFloatToByte(float value) {
        return (int) (value * COMPONENT_RANGE) & COMPONENT_MASK;
    }

    // converts an integer component in 0..255 to a normalised float in 0.0..1.0
    static float byteToNormalizedFloat(int value) {
        return (float) value * COMPONENT_RANGE_INVERSE;
    }
}
