package com.bdmajora.impetus.umbra.gl.image;

import java.util.function.IntSupplier;

// Where the images a single program declares get registered
// The pipeline offers EVERY image it knows about to every program, and the implementation decides which ones this
// particular program actually references, allocating a unit only for those
// That is the whole point of the per-program model: a gbuffers_basic that never mentions floodfill_img consumes no
// image unit for it, where the older global scheme reserved a unit for every declared image in every program and
// ran out on drivers with a small GL_MAX_IMAGE_UNITS
public interface ImageHolder {
    // Whether this program actually declares an image uniform by that name — the filter the model depends on
    boolean hasImage(String name);

    // Registers an image under that name if this program declares it, and does nothing otherwise — so callers can
    // offer everything without checking first
    // textureID is a supplier resolved at every bind rather than an id captured now: render-target images follow
    // buffer flips, so the texture behind a given name legitimately changes between frames
    // internalFormat is the sized internal format glBindImageTexture requires, and it must match the format in the
    // shader's own layout qualifier — a mismatch is undefined behaviour rather than a GL error
    void addTextureImage(IntSupplier textureID, int internalFormat, String name);
}
