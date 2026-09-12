package com.bdmajora.impetus.umbra.gl.image;

import com.bdmajora.impetus.lwjgl.GL15;

import java.util.function.IntSupplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// One image uniform's binding for one program
// The texture id comes from a supplier rather than a stored int, and update() runs at every program use rather
// than once per frame, for two reasons: the texture behind a render-target image name CHANGES when its buffer
// flips, and an unrelated draw between two uses of this program may have rebound the unit underneath us
// Iris re-binds at every use for exactly the same reasons
public class ImageBinding {
    private final int imageUnit;
    // Must match the image's declared format; a mismatch here is undefined behaviour rather than a GL error
    private final int internalFormat;
    private final IntSupplier textureID;

    public ImageBinding(int imageUnit, int internalFormat, IntSupplier textureID) {
        this.imageUnit = imageUnit;
        this.internalFormat = internalFormat;
        this.textureID = textureID;
    }

    // glBindImageTexture with the supplier's current texture
    public void update() {
        // Always layered: a layered binding exposes a 3D/array image whole, which is what every custom image
        // declared with a depth needs, and it is harmless for a 2D image (layer 0 is the only layer). Umbra binds
        // layered unconditionally for the same reason.
        LWJGL.glBindImageTexture(this.imageUnit, this.textureID.getAsInt(), 0, true, 0,
                GL15.GL_READ_WRITE, this.internalFormat);
    }
}
