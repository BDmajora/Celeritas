package com.bdmajora.impetus.impl.render.texture;

import com.bdmajora.impetus.engine.impl.ImpetusRuntimeOptions;
import com.bdmajora.impetus.engine.impl.gui.ImpetusGameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureMap;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

/**
 * Applies the user-selected texture minification/magnification filtering and anisotropic filtering to the block
 * atlas texture object. Called whenever the atlas is (re)built and whenever the options are applied.
 *
 * <p>These parameters are stored on the GL texture object itself, so they persist across binds; the block atlas
 * is re-stitched on resource reload, which is why {@code TextureAtlasMixin} re-applies them there.
 */
public final class BlockAtlasFiltering {
    private static Boolean anisotropySupported;
    private static float maxAnisotropy = 1.0f;

    private BlockAtlasFiltering() {
    }

    /** Re-applies filtering to the currently loaded block atlas (safe to call on the client thread). */
    public static void reapplyToBlockAtlas() {
        var mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }

        TextureMap atlas = mc.getTextureMapBlocks();
        if (atlas != null) {
            apply(atlas.getGlTextureId());
        }
    }

    public static void apply(int glTextureId) {
        if (glTextureId <= 0) {
            return;
        }

        boolean hasMipmaps = Minecraft.getMinecraft().gameSettings.mipmapLevels > 0;

        int minFilter = switch (ImpetusRuntimeOptions.textureFiltering) {
            case DEFAULT -> hasMipmaps ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST;
            case BILINEAR -> hasMipmaps ? GL11.GL_LINEAR_MIPMAP_NEAREST : GL11.GL_LINEAR;
            case TRILINEAR -> hasMipmaps ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR;
        };

        int magFilter = ImpetusRuntimeOptions.pixelFiltering == ImpetusGameOptions.PixelFilteringMode.LINEAR
                ? GL11.GL_LINEAR
                : GL11.GL_NEAREST;

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter);

        if (isAnisotropySupported()) {
            float requested = Math.min(ImpetusRuntimeOptions.anisotropyLevel(), maxAnisotropy);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D,
                    EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, Math.max(1.0f, requested));
        }
    }

    private static boolean isAnisotropySupported() {
        if (anisotropySupported == null) {
            try {
                var caps = GLContext.getCapabilities();
                anisotropySupported = caps != null && caps.GL_EXT_texture_filter_anisotropic;
                if (anisotropySupported) {
                    maxAnisotropy = GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
                }
            } catch (Throwable t) {
                anisotropySupported = false;
            }
        }

        return anisotropySupported;
    }
}
