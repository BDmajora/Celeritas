package com.bdmajora.impetus.umbra.gl.blending;

import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL14;

import java.util.Locale;

/** OptiFine/Umbra blend mode tuple from shaders.properties. */
public final class BlendMode {
    private final int srcRgb;
    private final int dstRgb;
    private final int srcAlpha;
    private final int dstAlpha;

    public BlendMode(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        this.srcRgb = srcRgb;
        this.dstRgb = dstRgb;
        this.srcAlpha = srcAlpha;
        this.dstAlpha = dstAlpha;
    }

    public int srcRgb() {
        return this.srcRgb;
    }

    public int dstRgb() {
        return this.dstRgb;
    }

    public int srcAlpha() {
        return this.srcAlpha;
    }

    public int dstAlpha() {
        return this.dstAlpha;
    }

    public static BlendMode parse(String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length == 2) {
            int src = function(parts[0]);
            int dst = function(parts[1]);
            return new BlendMode(src, dst, src, dst);
        }
        if (parts.length == 4) {
            return new BlendMode(
                    function(parts[0]), function(parts[1]),
                    function(parts[2]), function(parts[3]));
        }
        throw new IllegalArgumentException("expected two or four blend factors, got " + parts.length);
    }

    private static int function(String name) {
        switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "ZERO":
                return GL11.GL_ZERO;
            case "ONE":
                return GL11.GL_ONE;
            case "SRC_COLOR":
                return GL11.GL_SRC_COLOR;
            case "ONE_MINUS_SRC_COLOR":
                return GL11.GL_ONE_MINUS_SRC_COLOR;
            case "DST_COLOR":
                return GL11.GL_DST_COLOR;
            case "ONE_MINUS_DST_COLOR":
                return GL11.GL_ONE_MINUS_DST_COLOR;
            case "SRC_ALPHA":
                return GL11.GL_SRC_ALPHA;
            case "ONE_MINUS_SRC_ALPHA":
                return GL11.GL_ONE_MINUS_SRC_ALPHA;
            case "DST_ALPHA":
                return GL11.GL_DST_ALPHA;
            case "ONE_MINUS_DST_ALPHA":
                return GL11.GL_ONE_MINUS_DST_ALPHA;
            case "SRC_ALPHA_SATURATE":
                return GL11.GL_SRC_ALPHA_SATURATE;
            case "CONSTANT_COLOR":
                return GL14.GL_CONSTANT_COLOR;
            case "ONE_MINUS_CONSTANT_COLOR":
                return GL14.GL_ONE_MINUS_CONSTANT_COLOR;
            case "CONSTANT_ALPHA":
                return GL14.GL_CONSTANT_ALPHA;
            case "ONE_MINUS_CONSTANT_ALPHA":
                return GL14.GL_ONE_MINUS_CONSTANT_ALPHA;
            default:
                throw new IllegalArgumentException("unknown blend factor " + name);
        }
    }
}
