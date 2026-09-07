package com.bdmajora.impetus.iris.gl.sampler;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The driver's sampler-related limits, queried once (Iris {@code gl/sampler/SamplerLimits}).
 * <p>
 * {@code GL_MAX_TEXTURE_IMAGE_UNITS} is the per-stage fragment limit and is the one that bounds how many distinct
 * samplers a single program may address. It is 32 on the machines this port has been tested against, which is fewer
 * than the number of names a pack can declare — Complementary at {@code COLORED_LIGHTING = 512} with world-space
 * reflections declares 32 colortex plus three depthtex, seven shadow samplers, {@code noisetex} and six custom
 * texture/image samplers, i.e. 49 names. No static unit assignment can satisfy that, which is why units are allocated
 * per program over only the names each program actually declares.
 */
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

    public static SamplerLimits get() {
        if (instance == null) {
            instance = new SamplerLimits();
        }
        return instance;
    }

    /** Discards the cached query, so a context recreation re-reads the limits. */
    public static void reset() {
        instance = null;
    }

    public int getMaxTextureUnits() {
        return this.maxTextureUnits;
    }

    public int getMaxDrawBuffers() {
        return this.maxDrawBuffers;
    }
}
