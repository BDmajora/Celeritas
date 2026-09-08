package com.bdmajora.impetus.umbra.gl.image;

import com.bdmajora.impetus.lwjgl.GL15;

import java.util.function.IntSupplier;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * One image uniform's binding for one program (Umbra {@code gl/image/ImageBinding}).
 * <p>
 * Re-resolved and re-bound at every program use rather than once per frame. Umbra does the same
 * ({@code ComputeProgram.use} -&gt; {@code images.update()}), and it matters here for the same reason it does there:
 * the texture behind a render-target image name changes when the buffer flips, and an unrelated draw between two
 * uses of this program may have rebound the unit.
 */
public class ImageBinding {
    private final int imageUnit;
    private final int internalFormat;
    private final IntSupplier textureID;

    public ImageBinding(int imageUnit, int internalFormat, IntSupplier textureID) {
        this.imageUnit = imageUnit;
        this.internalFormat = internalFormat;
        this.textureID = textureID;
    }

    public void update() {
        // Always layered: a layered binding exposes a 3D/array image whole, which is what every custom image
        // declared with a depth needs, and it is harmless for a 2D image (layer 0 is the only layer). Umbra binds
        // layered unconditionally for the same reason.
        LWJGL.glBindImageTexture(this.imageUnit, this.textureID.getAsInt(), 0, true, 0,
                GL15.GL_READ_WRITE, this.internalFormat);
    }
}
