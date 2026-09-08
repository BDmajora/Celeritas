package com.bdmajora.impetus.umbra.gl.texture;

import com.bdmajora.impetus.umbra.gl.GlResource;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.MemoryStack;

import java.nio.ByteBuffer;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * A 1×1 solid-color texture. Used as the fallback {@code normals}/{@code specular} inputs during the gbuffer stage —
 * the same defaults OptiFine substitutes when a resource pack ships no PBR maps (flat up-normal, black specular) —
 * so pack PBR math reads well-defined values instead of whatever an unbound sampler returns.
 */
public class PlainTexture extends GlResource {
    public PlainTexture(int red, int green, int blue, int alpha) {
        setHandle(LWJGL.glGenTextures());
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, getGlId());
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        try (MemoryStack stack = LWJGL.stackPush()) {
            ByteBuffer pixel = stack.malloc(4);
            pixel.put((byte) red).put((byte) green).put((byte) blue).put((byte) alpha);
            pixel.flip();
            LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        }
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    public int getTextureId() {
        return getGlId();
    }

    @Override
    protected void destroyInternal() {
        LWJGL.glDeleteTextures(getGlId());
    }
}
