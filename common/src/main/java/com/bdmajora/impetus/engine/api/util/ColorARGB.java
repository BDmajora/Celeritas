package com.bdmajora.impetus.engine.api.util;

// utilities for packing and unpacking colour components from packed integer colours in ARGB format
// this packed format is used by most of Minecraft, but special care must be taken to pack it into ABGR
// before passing it to OpenGL attributes
// | 32        | 24        | 16        | 8          |
// | 0110 1100 | 0110 1100 | 0110 1100 | 0110 1100  |
// | Alpha     | Red       | Green     | Blue       |
public class ColorARGB implements ColorU8 {
    private static final int ALPHA_COMPONENT_OFFSET = 24;
    private static final int RED_COMPONENT_OFFSET = 16;
    private static final int GREEN_COMPONENT_OFFSET = 8;
    private static final int BLUE_COMPONENT_OFFSET = 0;

    // packs the colour components into big-endian format for consumption by OpenGL
    public static int pack(int r, int g, int b, int a) {
        return (a & COMPONENT_MASK) << ALPHA_COMPONENT_OFFSET |
                (r & COMPONENT_MASK) << RED_COMPONENT_OFFSET |
                (g & COMPONENT_MASK) << GREEN_COMPONENT_OFFSET |
                (b & COMPONENT_MASK) << BLUE_COMPONENT_OFFSET;
    }

    // packs the colour components into big-endian format for consumption by OpenGL, with the alpha
    // channel fully opaque
    public static int pack(int r, int g, int b) {
        return pack(r, g, b, (1 << ColorU8.COMPONENT_BITS) - 1);
    }

    // the alpha component of a packed 32-bit ARGB colour, in 0..255
    public static int unpackAlpha(int color) {
        return color >> ALPHA_COMPONENT_OFFSET & COMPONENT_MASK;
    }

    // the red component of a packed 32-bit ARGB colour, in 0..255
    public static int unpackRed(int color) {
        return color >> RED_COMPONENT_OFFSET & COMPONENT_MASK;
    }

    // the green component of a packed 32-bit ARGB colour, in 0..255
    public static int unpackGreen(int color) {
        return color >> GREEN_COMPONENT_OFFSET & COMPONENT_MASK;
    }

    // the blue component of a packed 32-bit ARGB colour, in 0..255
    public static int unpackBlue(int color) {
        return color >> BLUE_COMPONENT_OFFSET & COMPONENT_MASK;
    }

    // re-packs an ARGB colour into ABGR with the given alpha component
    public static int toABGR(int color, int alpha) {
        return Integer.reverseBytes(color << 8 | alpha);
    }

    public static int toABGR(int color) {
        return Integer.reverseBytes(Integer.rotateLeft(color, 8));
    }

    // packs the colour components into ARGB format
    // rgb is the red/green/blue part, alpha the alpha component
    public static int withAlpha(int rgb, int alpha) {
        return (alpha << ALPHA_COMPONENT_OFFSET) | (rgb & ~(COMPONENT_MASK << ALPHA_COMPONENT_OFFSET));
    }
}
