package org.taumc.celeritas.iris.targets;

import org.taumc.celeritas.iris.gl.GlResource;
import org.taumc.celeritas.lwjgl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The {@code noisetex} sampler: a tiling RGBA random-noise texture (OptiFine's generated noise, default 256×256).
 * Packs use it pervasively for dithering, cloud shapes, and water waves. Seeded deterministically so the noise is
 * stable across pipeline rebuilds (pack switches don't make dither patterns jump).
 */
public class NoiseTexture extends GlResource {
    public static final int DEFAULT_RESOLUTION = 256;

    public NoiseTexture(int resolution) {
        setHandle(LWJGL.glGenTextures());
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, getGlId());
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

        ByteBuffer data = ByteBuffer.allocateDirect(resolution * resolution * 4).order(ByteOrder.nativeOrder());
        Random random = new Random(0);
        byte[] pixels = new byte[resolution * resolution * 4];
        random.nextBytes(pixels);
        data.put(pixels);
        data.flip();

        LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, resolution, resolution, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
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
