package com.bdmajora.impetus.umbra.targets;

import com.bdmajora.impetus.umbra.gl.GlResource;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL12;

import java.nio.ByteBuffer;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A depth texture that programs sample: depthtex0/1/2 and shadowtex0/1
// There are several because OptiFine snapshots the scene depth at different points in the frame and packs rely on
// the differences — depthtex0 is everything, depthtex1 excludes translucents, depthtex2 also excludes the hand.
// Subtracting one from another is how packs detect water surfaces and hand-occluded pixels
public class DepthTexture extends GlResource {
    private static final ByteBuffer NULL_BUFFER = null;

    private final int internalFormat;
    private final int pixelFormat;
    private final int pixelType;
    private int width;
    private int height;

    public DepthTexture(int width, int height, int internalFormat, int pixelFormat, int pixelType) {
        this.internalFormat = internalFormat;
        this.pixelFormat = pixelFormat;
        this.pixelType = pixelType;
        this.width = width;
        this.height = height;

        setHandle(LWJGL.glGenTextures());
        allocate();
    }

    // Creates the storage at the current size
    private void allocate() {
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, getGlId());
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, this.internalFormat, this.width, this.height, 0,
                this.pixelFormat, this.pixelType, NULL_BUFFER);
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    // Reallocates; contents are lost
    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
        allocate();
    }

    // For binding as a sampler
    public int getTextureId() {
        return getGlId();
    }

    // Frees the texture
    @Override
    protected void destroyInternal() {
        LWJGL.glDeleteTextures(getGlId());
    }
}
