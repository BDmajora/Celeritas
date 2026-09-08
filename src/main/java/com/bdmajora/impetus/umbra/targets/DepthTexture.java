package com.bdmajora.impetus.umbra.targets;

import com.bdmajora.impetus.umbra.gl.GlResource;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL12;

import java.nio.ByteBuffer;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * A depth texture used as a sampler ({@code depthtex0/1/2}, {@code shadowtex0/1}). OptiFine keeps copies of the scene
 * depth at different points in the frame (before translucents, before hand, …); each copy is one of these.
 */
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

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
        allocate();
    }

    public int getTextureId() {
        return getGlId();
    }

    @Override
    protected void destroyInternal() {
        LWJGL.glDeleteTextures(getGlId());
    }
}
