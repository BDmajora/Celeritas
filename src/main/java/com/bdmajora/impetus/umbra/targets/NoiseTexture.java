package com.bdmajora.impetus.umbra.targets;

import com.bdmajora.impetus.umbra.gl.GlResource;
import com.bdmajora.impetus.lwjgl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The `noisetex` sampler: a tiling RGBA random-noise texture, OptiFine's generated noise
// Packs lean on it constantly — dithering, cloud shapes, water wave offsets
public class NoiseTexture extends GlResource {
    // OptiFine's default; a pack overrides it with noiseTextureResolution
    public static final int DEFAULT_RESOLUTION = 256;

    // LINEAR, not NEAREST: packs sample this at non-integer coordinates and expect the values to blend
    // REPEAT is what makes it tile, which is the whole point of a small noise texture
    public NoiseTexture(int resolution) {
        setHandle(LWJGL.glGenTextures());
        LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, getGlId());
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

        // Direct and native-ordered because it goes straight to glTexImage2D; a heap buffer would be copied
        ByteBuffer data = ByteBuffer.allocateDirect(resolution * resolution * 4).order(ByteOrder.nativeOrder());
        // Fixed seed, deliberately. The noise has to be identical across pipeline rebuilds, or switching packs
        // (or reloading one) would make every dither pattern in the scene jump
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
