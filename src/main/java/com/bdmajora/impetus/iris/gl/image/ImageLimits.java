package com.bdmajora.impetus.iris.gl.image;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The driver's {@code GL_MAX_IMAGE_UNITS}, queried once (Iris {@code gl/image/ImageLimits}).
 * <p>
 * Image units are a namespace entirely separate from texture units: {@code glBindImageTexture} does not touch the
 * texture-unit selector, so none of the {@link com.bdmajora.impetus.iris.gl.GlTextureUnits} cache-desync hazards
 * apply here. That is what makes per-program image allocation safe on 1.12.2 even though per-program *sampler*
 * allocation is not.
 */
public final class ImageLimits {
    private static final int GL_MAX_IMAGE_UNITS = 0x8D57;

    private static ImageLimits instance;

    private final int maxImageUnits;

    private ImageLimits() {
        // A driver without image support reports 0 (or fails the query and leaves 0), which the builder turns into a
        // hard error naming the offending image rather than a silent bind to unit 0.
        this.maxImageUnits = Math.max(0, LWJGL.glGetInteger(GL_MAX_IMAGE_UNITS));
    }

    public static ImageLimits get() {
        if (instance == null) {
            instance = new ImageLimits();
        }
        return instance;
    }

    /** Discards the cached query, so a context recreation re-reads the limit. */
    public static void reset() {
        instance = null;
    }

    public int getMaxImageUnits() {
        return this.maxImageUnits;
    }
}
