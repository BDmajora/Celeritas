package com.bdmajora.impetus.umbra.gl.image;

import java.util.function.IntSupplier;

// Where the images a single program declares get registered; the pipeline offers EVERY image to every program and only referenced ones get a unit, unlike the old global scheme that ran out on drivers with a small GL_MAX_IMAGE_UNITS
public interface ImageHolder {
    // Whether this program actually declares an image uniform by that name — the filter the model depends on
    boolean hasImage(String name);

    // Registers an image under that name only if this program declares it, so callers offer everything blindly; textureID is a supplier since render-target images follow buffer flips, and internalFormat must match the shader's layout qualifier or behaviour is undefined
    void addTextureImage(IntSupplier textureID, int internalFormat, String name);
}
