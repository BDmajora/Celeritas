package com.bdmajora.impetus.iris.gl.image;

import java.util.function.IntSupplier;

/**
 * Sink for the images a single program declares (Iris {@code gl/image/ImageHolder}).
 * <p>
 * The registration helpers offer every image the pipeline knows about to each program; the implementation decides
 * which ones that program actually references and allocates a unit only for those. That is the whole point of the
 * per-program model — a {@code gbuffers_basic} that never mentions {@code floodfill_img} consumes no image unit for
 * it, where the older global scheme reserved a unit for every declared image in every program.
 */
public interface ImageHolder {
    /** {@return whether this program actually declares an image uniform named {@code name}} */
    boolean hasImage(String name);

    /**
     * Registers an image under {@code name} if this program declares it, otherwise does nothing.
     *
     * @param textureID      resolved at every bind, not captured now — render-target images follow buffer flips, so
     *                       the texture behind a given name legitimately changes between frames
     * @param internalFormat the image's sized internal format, which {@code glBindImageTexture} requires and which
     *                       must match the format declared in the shader's {@code layout} qualifier
     */
    void addTextureImage(IntSupplier textureID, int internalFormat, String name);
}
