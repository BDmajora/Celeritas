package com.bdmajora.impetus.umbra.gl.texture;

import com.bdmajora.impetus.umbra.gl.GlResource;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL14;
import com.bdmajora.impetus.lwjgl.MemoryStack;

import java.nio.ByteBuffer;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// A 1x1 depth texture holding depth 1.0, bound to shadowtex0/shadowtex1 whenever there is no real shadow pass
// 1.0 is the depth-clear value, i.e. "nothing was ever drawn here", which every pack reads as fully lit — the same
// thing OptiFine shows when a pack declares no shadow program
// The pipeline layers raw or compare sampler objects over it per program, so both a raw depth fetch and a shadow2D
// compare land on that same fully-lit answer
public class StubShadowMap extends GlResource {
    public StubShadowMap() {
        setHandle(LWJGL.glGenTextures());
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, getGlId());
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        try (MemoryStack stack = LWJGL.stackPush()) {
            ByteBuffer depth = stack.malloc(Float.BYTES);
            depth.putFloat(1.0f);
            depth.flip();
            LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, 1, 1, 0,
                    GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
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
