package org.taumc.celeritas.iris.gl.texture;

import org.taumc.celeritas.iris.gl.GlResource;
import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL14;
import org.taumc.celeritas.lwjgl.MemoryStack;

import java.nio.ByteBuffer;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * A 1×1 depth texture holding depth 1.0 with hardware compare enabled ({@code LEQUAL}), bound to the
 * {@code shadowtex0}/{@code shadowtex1} units while there is no real shadow pass. Any {@code shadow2D} lookup then
 * compares {@code ref <= 1.0} — always true — so packs see "fully lit" (OptiFine's no-shadow-map behavior) instead of
 * the undefined result of sampling an unbound shadow sampler (which reads as fully shadowed on most drivers).
 */
public class StubShadowMap extends GlResource {
    public StubShadowMap() {
        setHandle(LWJGL.glGenTextures());
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, getGlId());
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_COMPARE_R_TO_TEXTURE);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
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
