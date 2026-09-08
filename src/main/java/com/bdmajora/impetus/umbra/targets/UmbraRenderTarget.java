package com.bdmajora.impetus.umbra.targets;

import com.bdmajora.impetus.umbra.gl.texture.InternalTextureFormat;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL12;

import java.nio.ByteBuffer;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * One shader-pack color buffer ("colortex" / "gcolor"). Like OptiFine's {@code dfbColorTexturesA}/{@code B}, each
 * target owns two GL textures so passes can ping-pong: composite N reads the "main" texture and writes the "alt",
 * then the {@code BufferFlipper} swaps which is which for composite N+1.
 */
public class UmbraRenderTarget {
    private static final ByteBuffer NULL_BUFFER = null;
    private static final int GL_LINEAR_MIPMAP_LINEAR = 0x2703;

    private final InternalTextureFormat internalFormat;
    private final int mainTexture;
    private final int altTexture;
    private final boolean linear;
    private int width;
    private int height;
    private boolean valid = true;
    private boolean mainMipmapped;
    private boolean altMipmapped;

    public UmbraRenderTarget(InternalTextureFormat internalFormat, int width, int height) {
        this.internalFormat = internalFormat;
        this.width = width;
        this.height = height;

        this.mainTexture = LWJGL.glGenTextures();
        this.altTexture = LWJGL.glGenTextures();

        this.linear = !internalFormat.isInteger();
        setupTexture(this.mainTexture, width, height, this.linear);
        setupTexture(this.altTexture, width, height, this.linear);

        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void setupTexture(int texture, int width, int height, boolean linear) {
        int filter = linear ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
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
        this.mainMipmapped = false;
        this.altMipmapped = false;
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    public void generateMipmaps(boolean alt) {
        requireValid();
        if (!this.linear) {
            return;
        }
        int texture = alt ? this.altTexture : this.mainTexture;
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        LWJGL.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        turnOnMips(alt);
    }

    public void turnOnMips(boolean alt) {
        if (alt) {
            this.altMipmapped = true;
        } else {
            this.mainMipmapped = true;
        }
    }

    public void resetMipmaps() {
        requireValid();
        if (this.mainMipmapped) {
            resetMipmapState(this.mainTexture);
            turnOffMips(false);
        }
        if (this.altMipmapped) {
            resetMipmapState(this.altTexture);
            turnOffMips(true);
        }
    }

    public void turnOffMips(boolean alt) {
        if (alt) {
            this.altMipmapped = false;
        } else {
            this.mainMipmapped = false;
        }
    }

    private void resetMipmapState(int texture) {
        int filter = this.linear ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
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
            throw new IllegalStateException("Tried to use a destroyed UmbraRenderTarget");
        }
    }
}
