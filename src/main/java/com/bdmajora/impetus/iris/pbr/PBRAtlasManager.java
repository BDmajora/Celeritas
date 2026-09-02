package com.bdmajora.impetus.iris.pbr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;

/**
 * Builds the {@code normals}/{@code specular} PBR atlases for the block atlas: for every stitched sprite, the
 * companion textures {@code <name>_n.png} / {@code <name>_s.png} are loaded (LabPBR/OldPBR resource-pack
 * convention) and uploaded into two atlas textures with the exact same layout as the base atlas, so the base
 * UVs address the PBR data directly. Sprites without companions keep the neutral defaults
 * (normals 127/127/255/255 = flat +Z, specular 0/0/0/0 = no reflectance), matching the pipeline's 1×1 fallbacks.
 *
 * <p>Rebuilt on every atlas stitch (resource reload). Animated sprites contribute their first frame; PBR
 * animation parity is a known follow-up. This is an original implementation for the 1.12.2 {@code TextureMap}
 * architecture — upstream Iris's {@code SpriteContents}-based atlas classes do not port.
 */
public final class PBRAtlasManager {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    // Spelled out rather than pulled from an LWJGL binding so this file stays independent of the GL wrapper split.
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    private static final int GL_TEXTURE_WRAP_S = 0x2802;
    private static final int GL_TEXTURE_WRAP_T = 0x2803;
    private static final int GL_NEAREST = 0x2600;
    private static final int GL_NEAREST_MIPMAP_NEAREST = 0x2700;
    private static final int GL_CLAMP_TO_EDGE = 0x812F;

    private static int normalsAtlas = -1;
    private static int specularAtlas = -1;
    private static int normalsCount;
    private static int specularCount;

    private PBRAtlasManager() {
    }

    /** {@return the normals atlas GL id, or {@code fallback} when no pack provided any normal maps} */
    public static int getNormalsAtlas(int fallback) {
        return normalsCount > 0 ? normalsAtlas : fallback;
    }

    /** {@return the specular atlas GL id, or {@code fallback} when no pack provided any specular maps} */
    public static int getSpecularAtlas(int fallback) {
        return specularCount > 0 ? specularAtlas : fallback;
    }

    public static void rebuild(Map<String, TextureAtlasSprite> sprites, int atlasWidth, int atlasHeight, int mipmapLevels) {
        try {
            destroy();

            normalsAtlas = allocateAtlas(atlasWidth, atlasHeight, mipmapLevels, 0xFF7F7FFF); // ARGB: flat +Z normal (127,127,255,255)
            specularAtlas = allocateAtlas(atlasWidth, atlasHeight, mipmapLevels, 0x00000000);
            normalsCount = 0;
            specularCount = 0;

            for (TextureAtlasSprite sprite : sprites.values()) {
                if (sprite.getIconWidth() <= 0 || sprite.getIconHeight() <= 0) {
                    continue;
                }
                if (uploadCompanion(sprite, "_n", normalsAtlas, mipmapLevels)) {
                    normalsCount++;
                }
                if (uploadCompanion(sprite, "_s", specularAtlas, mipmapLevels)) {
                    specularCount++;
                }
            }

            // AFTER every upload, not inside allocateAtlas: TextureUtil.uploadTextureMipmap re-applies the filter and
            // wrap modes from its own blur/clamp arguments on each call, so anything set at allocation time is
            // overwritten by the first companion sprite. This has to be the last word on both textures.
            GlStateManager.bindTexture(normalsAtlas);
            applyPbrSampling(mipmapLevels);
            GlStateManager.bindTexture(specularAtlas);
            applyPbrSampling(mipmapLevels);

            GlStateManager.bindTexture(0);

            if (normalsCount > 0 || specularCount > 0) {
                LOGGER.info("[Iris] PBR atlases built: {} normal map(s), {} specular map(s)", normalsCount, specularCount);
            }
        } catch (Throwable t) {
            // PBR is an enhancement; a failure here must never break the atlas reload.
            LOGGER.error("[Iris] Failed to build PBR atlases; packs will see flat surfaces", t);
            destroy();
        }
    }

    public static void destroy() {
        if (normalsAtlas != -1) {
            TextureUtil.deleteTexture(normalsAtlas);
            normalsAtlas = -1;
        }
        if (specularAtlas != -1) {
            TextureUtil.deleteTexture(specularAtlas);
            specularAtlas = -1;
        }
        normalsCount = 0;
        specularCount = 0;
    }

    private static int allocateAtlas(int width, int height, int mipmapLevels, int fillArgb) {
        int texture = TextureUtil.glGenTextures();
        TextureUtil.allocateTextureImpl(texture, mipmapLevels, width, height);

        // Fill level 0 (and mips) with the neutral value so unmapped sprites read as flat/non-reflective.
        GlStateManager.bindTexture(texture);
        TextureUtil.uploadTextureMipmap(neutralMipLevels(width, height, mipmapLevels, fillArgb),
                width, height, 0, 0, false, false);
        return texture;
    }

    /**
     * Iris pins the PBR atlas sampler to nearest + clamp-to-edge
     * ({@code PBRAtlasTexture#upload}: {@code getSamplerCache().getClampToEdge(FilterMode.NEAREST)}). Reproduce that.
     * <p>
     * What the uploads leave behind instead: {@code TextureUtil.uploadTextureMipmap(..., blur, clamp)} applies its
     * own sampling on every call, and this class passes {@code false, false}. Through
     * {@code setTextureBlurMipmap(false, true)} that resolves to magnification {@code NEAREST} — already right — but
     * minification {@code NEAREST_MIPMAP_LINEAR}, and {@code setTextureClamped(false)} leaves wrapping at
     * {@code REPEAT}. The magnification filter was never the problem; the other two are.
     * <p>
     * {@code NEAREST_MIPMAP_LINEAR} <em>interpolates between mip levels</em>. These atlases hold labPBR channels, not
     * colour, and in labPBR the specular alpha channel <em>is emissiveness</em>. Coarser mips average neighbouring
     * sprites together, so a sprite with no {@code _s} companion — which should read the neutral fill and never
     * glow — starts blending in its atlas neighbours' emission the moment the sampler drops to a coarser level. Mip
     * level is chosen from screen-space UV derivatives, so it changes with viewing angle: the glow appears when the
     * camera turns and disappears when it turns back, while the draw call itself is byte-for-byte identical. That is
     * the failure servers hit when their scenery is built from custom item models.
     * <p>
     * {@code CLAMP_TO_EDGE} matches Iris for the neighbouring reason: a UV a hair past a sprite's edge must clamp
     * inside that sprite rather than wrap to the far side of the atlas.
     */
    private static void applyPbrSampling(int mipmapLevels) {
        // Nearest in both directions. With mipmaps present minification must be NEAREST_MIPMAP_NEAREST, not plain
        // NEAREST_MIPMAP_LINEAR — the "_LINEAR" half is the level blend, and that is the whole bug.
        GlStateManager.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GlStateManager.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                mipmapLevels > 0 ? GL_NEAREST_MIPMAP_NEAREST : GL_NEAREST);
        GlStateManager.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GlStateManager.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private static int[][] neutralMipLevels(int width, int height, int mipmapLevels, int fillArgb) {
        int levelCount = Math.max(1, mipmapLevels + 1);
        int[][] levels = new int[levelCount][];
        for (int level = 0; level < levelCount; level++) {
            int levelWidth = Math.max(1, width >> level);
            int levelHeight = Math.max(1, height >> level);
            int[] fill = new int[levelWidth * levelHeight];
            java.util.Arrays.fill(fill, fillArgb);
            levels[level] = fill;
        }
        return levels;
    }

    private static boolean uploadCompanion(TextureAtlasSprite sprite, String suffix, int atlasTexture, int mipmapLevels) {
        BufferedImage image = readCompanion(sprite.getIconName(), suffix);
        if (image == null) {
            return false;
        }

        int iconWidth = sprite.getIconWidth();
        int iconHeight = sprite.getIconHeight();

        int[] pixels = extractFrame(image, iconWidth, iconHeight);
        if (pixels == null) {
            return false;
        }

        // Sprite sizes are already constrained by the atlas's chosen mip level count, so the same generator the
        // base atlas uses is safe here.
        int[][] levels;
        try {
            levels = mipmapLevels > 0
                    ? TextureUtil.generateMipmapData(mipmapLevels, iconWidth, new int[][] { pixels })
                    : new int[][] { pixels };
        } catch (Exception e) {
            levels = new int[][] { pixels };
        }

        GlStateManager.bindTexture(atlasTexture);
        TextureUtil.uploadTextureMipmap(levels, iconWidth, iconHeight, sprite.getOriginX(), sprite.getOriginY(), false, false);
        return true;
    }

    /**
     * Scales the companion image to the sprite's base resolution and crops to the first animation frame.
     * Companion maps commonly ship at a different resolution than the base texture; nearest-neighbour scaling
     * preserves LabPBR-encoded channel data better than any smoothing filter would.
     */
    private static int[] extractFrame(BufferedImage image, int iconWidth, int iconHeight) {
        int srcWidth = image.getWidth();
        int srcHeight = image.getHeight();
        if (srcWidth <= 0 || srcHeight <= 0) {
            return null;
        }

        int[] out = new int[iconWidth * iconHeight];
        for (int y = 0; y < iconHeight; y++) {
            int srcY = y * srcWidth / iconWidth; // scale by width ratio; crops to frame 0 of animation strips
            if (srcY >= srcHeight) {
                srcY = srcHeight - 1;
            }
            for (int x = 0; x < iconWidth; x++) {
                int srcX = x * srcWidth / iconWidth;
                out[y * iconWidth + x] = image.getRGB(srcX, srcY);
            }
        }
        return out;
    }

    private static BufferedImage readCompanion(String iconName, String suffix) {
        ResourceLocation icon = new ResourceLocation(iconName);
        ResourceLocation location = new ResourceLocation(icon.getNamespace(),
                "textures/" + icon.getPath() + suffix + ".png");

        try (IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(location)) {
            return ImageIO.read(resource.getInputStream());
        } catch (IOException e) {
            return null; // the overwhelmingly common case: no companion texture
        }
    }
}
