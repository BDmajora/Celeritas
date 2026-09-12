package com.bdmajora.impetus.umbra.gl.blending;

import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL14;

import java.util.Locale;

// The blend factors a pack declares for one program via `blend.<program> = SRC DST` or the four-factor form
// Four factors rather than two because GL can blend colour and alpha with separate functions, and packs use that:
// a pass commonly blends RGB normally while writing alpha as ONE/ZERO so the alpha channel carries data instead of
// coverage
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

    // GL source factor for RGB
    public int srcRgb() {
        return this.srcRgb;
    }

    // GL destination factor for RGB
    public int dstRgb() {
        return this.dstRgb;
    }

    // GL source factor for alpha
    public int srcAlpha() {
        return this.srcAlpha;
    }

    // GL destination factor for alpha
    public int dstAlpha() {
        return this.dstAlpha;
    }

    // Accepts the two-factor and four-factor forms OptiFine defines; anything else is a malformed directive
    // Throws rather than returning null, because the caller logs the message and drops just this one directive
    public static BlendMode parse(String value) {
        String[] parts = value.trim().split("\\s+");
        // Two factors mean the same pair applies to both colour and alpha, which is GL's own glBlendFunc default
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

    // Factor name to GL enum. Upper-cased through ROOT so a Turkish locale cannot map "i" to a dotted capital and
    // make SRC_ALPHA stop matching
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
