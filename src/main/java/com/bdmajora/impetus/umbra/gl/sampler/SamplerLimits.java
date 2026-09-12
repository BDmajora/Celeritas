package com.bdmajora.impetus.umbra.gl.sampler;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Driver sampler limits, queried once; GL_MAX_TEXTURE_IMAGE_UNITS (32 on tested hardware) caps distinct samplers per program, and Complementary at COLORED_LIGHTING=512 declares 49, so units must be allocated per program over the names it uses
public final class SamplerLimits {
    private static final int GL_MAX_TEXTURE_IMAGE_UNITS = 0x8872;
    private static final int GL_MAX_DRAW_BUFFERS = 0x8824;

    private static SamplerLimits instance;

    private final int maxTextureUnits;
    private final int maxDrawBuffers;

    private SamplerLimits() {
        this.maxTextureUnits = LWJGL.glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        this.maxDrawBuffers = LWJGL.glGetInteger(GL_MAX_DRAW_BUFFERS);
    }

    // Queried on first use and cached
    public static SamplerLimits get() {
        if (instance == null) {
            instance = new SamplerLimits();
        }
        return instance;
    }

    // Discards the cached query so a context recreation re-reads the limits
    public static void reset() {
        instance = null;
    }

    // GL_MAX_TEXTURE_IMAGE_UNITS
    public int getMaxTextureUnits() {
        return this.maxTextureUnits;
    }

    // GL_MAX_DRAW_BUFFERS
    public int getMaxDrawBuffers() {
        return this.maxDrawBuffers;
    }
}
