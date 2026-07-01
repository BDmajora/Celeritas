package org.taumc.celeritas.iris.targets;

import org.taumc.celeritas.iris.gl.texture.InternalTextureFormat;
import org.taumc.celeritas.lwjgl.GL11;

import java.nio.ByteBuffer;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * One shader-pack color buffer ("colortex" / "gcolor"). Like OptiFine's {@code dfbColorTexturesA}/{@code B}, each
 * target owns two GL textures so passes can ping-pong: composite N reads the "main" texture and writes the "alt",
 * then the {@code BufferFlipper} swaps which is which for composite N+1.
 */
public class IrisRenderTarget {
    private static final ByteBuffer NULL_BUFFER = null;

    private final InternalTextureFormat internalFormat;
    private final int mainTexture;
    private final int altTexture;
    private int width;
    private int height;
    private boolean valid = true;

    public IrisRenderTarget(InternalTextureFormat internalFormat, int width, int height) {
        this.internalFormat = internalFormat;
        this.width = width;
        this.height = height;

        this.mainTexture = LWJGL.glGenTextures();
        this.altTexture = LWJGL.glGenTextures();

        boolean linear = !internalFormat.isInteger();
        setupTexture(this.mainTexture, width, height, linear);
        setupTexture(this.altTexture, width, height, linear);

        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void setupTexture(int texture, int width, int height, boolean linear) {
        int filter = linear ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, this.internalFormat.getInternalFormat(), width, height, 0,
                this.internalFormat.getPixelFormat(), this.internalFormat.getPixelType(), NULL_BUFFER);
    }

    public void resize(int newWidth, int newHeight) {
        requireValid();
        this.width = newWidth;
        this.height = newHeight;
        boolean linear = !this.internalFormat.isInteger();
        setupTexture(this.mainTexture, newWidth, newHeight, linear);
        setupTexture(this.altTexture, newWidth, newHeight, linear);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    public InternalTextureFormat getInternalFormat() {
        return this.internalFormat;
    }

    public int getMainTexture() {
        requireValid();
        return this.mainTexture;
    }

    public int getAltTexture() {
        requireValid();
        return this.altTexture;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public void destroy() {
        requireValid();
        this.valid = false;
        LWJGL.glDeleteTextures(this.mainTexture);
        LWJGL.glDeleteTextures(this.altTexture);
    }

    private void requireValid() {
        if (!this.valid) {
            throw new IllegalStateException("Tried to use a destroyed IrisRenderTarget");
        }
    }
}
