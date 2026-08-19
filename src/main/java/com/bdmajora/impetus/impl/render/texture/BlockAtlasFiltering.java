package com.bdmajora.impetus.impl.render.texture;

import com.bdmajora.impetus.engine.impl.ImpetusRuntimeOptions;
import com.bdmajora.impetus.engine.impl.gui.ImpetusGameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureMap;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

/**
 * Applies the block atlas' sampler state: vanilla minification, the user-selected magnification, and an explicit
 * anisotropy reset. Called whenever the atlas is (re)built and whenever the options are applied.
 *
 * <p><b>Why minification is not configurable.</b> The atlas packs every sprite edge to edge with no border, so a
 * sampler that reads outside the 16x16 sprite rect returns a <em>different block's</em> texels. Vanilla's
 * {@code GL_NEAREST_MIPMAP_LINEAR} never does: it takes one texel per level. Anisotropic filtering does — it walks
 * a line of samples along the major axis of the pixel footprint, and at grazing angles that line runs off the
 * sprite — and so does any {@code GL_LINEAR_MIPMAP_*} filter, whose bilinear tap straddles the border on the
 * sprite's edge texels. In game it looks like coloured dashes tracing the block grid on distant terrain.
 *
 * <p>Measured with {@code tools/anisocheck.c} (RTX 5070; 512x512 atlas, 16x16 sprites, 4 mip levels, ground plane
 * of one-quad blocks) as the share of ground pixels showing a neighbouring sprite's colour, excluding the handful
 * of rows at the horizon where a whole block falls below one pixel: {@code NEAREST_MIPMAP_LINEAR} is exactly
 * 0/228000 at 1x anisotropy and 9.9% at 8x; {@code LINEAR_MIPMAP_LINEAR} bleeds at 1x already. Supporting either
 * properly needs a padded atlas — OptiFine grows every sprite by a replicated border — which is a stitcher change;
 * until then vanilla's filter is the only correct minification here.
 *
 * <p>Magnification stays configurable: {@code GL_LINEAR} there only reaches one texel past the border, and it is
 * off by default.
 *
 * <p>These parameters are stored on the GL texture object itself, so they persist across binds; the block atlas
 * is re-stitched on resource reload, which is why {@code TextureAtlasMixin} re-applies them there.
 */
public final class BlockAtlasFiltering {
    private static Boolean anisotropySupported;

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

        int minFilter = hasMipmaps ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST;
        int magFilter = ImpetusRuntimeOptions.pixelFiltering == ImpetusGameOptions.PixelFilteringMode.LINEAR
                ? GL11.GL_LINEAR
                : GL11.GL_NEAREST;

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter);

        // Written every time rather than left alone: the parameter lives on the texture object, so a level set by
        // a driver profile or another mod would otherwise survive and bleed the atlas.
        if (isAnisotropySupported()) {
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D,
                    EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, 1.0f);
        }
    }

    private static boolean isAnisotropySupported() {
        if (anisotropySupported == null) {
            try {
                var caps = GLContext.getCapabilities();
                anisotropySupported = caps != null && caps.GL_EXT_texture_filter_anisotropic;
            } catch (Throwable t) {
                anisotropySupported = false;
            }
        }

        return anisotropySupported;
    }
}
