package com.bdmajora.impetus.umbra.gl.image;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The driver's GL_MAX_IMAGE_UNITS, queried once and cached
// Image units are a namespace entirely separate from texture units: glBindImageTexture does not touch the
// texture-unit selector, so none of GlTextureUnits' cache-desync hazards apply here. That is what makes
// per-program image allocation safe on 1.12.2 even though per-program SAMPLER allocation is not
public final class ImageLimits {
    // Spelled as a literal because the generated GL constant classes do not carry it
    private static final int GL_MAX_IMAGE_UNITS = 0x8D57;

    // Lazily built rather than a static initializer, because the query needs a live GL context
    private static ImageLimits instance;

    private final int maxImageUnits;

    private ImageLimits() {
        // A driver without image support reports 0 (or fails the query and leaves 0), which the builder turns into a
        // hard error naming the offending image rather than a silent bind to unit 0.
        this.maxImageUnits = Math.max(0, LWJGL.glGetInteger(GL_MAX_IMAGE_UNITS));
    }

    // Queried on first use and cached
    public static ImageLimits get() {
        if (instance == null) {
            instance = new ImageLimits();
        }
        return instance;
    }

    // Drops the cached query so a context recreation re-reads the limit, which can differ on a different GPU
    public static void reset() {
        instance = null;
    }

    // GL_MAX_IMAGE_UNITS
    public int getMaxImageUnits() {
        return this.maxImageUnits;
    }
}
