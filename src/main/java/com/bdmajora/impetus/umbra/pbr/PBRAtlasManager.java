package com.bdmajora.impetus.umbra.pbr;

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

// Builds the normals and specular PBR atlases alongside the block atlas, with the exact same layout so base
// UVs address the PBR data directly. Sprites without _n/_s companions keep the flat-normal and no-reflectance defaults
// Rebuilt on every stitch; animated sprites contribute only their first frame
public final class PBRAtlasManager {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

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

    // The normals atlas GL id, or the caller's fallback when no resource pack shipped a single normal map — the
    // fallback being the neutral 1x1 texture, so the sampler is bound either way
    public static int getNormalsAtlas(int fallback) {
        return normalsCount > 0 ? normalsAtlas : fallback;
    }

    // The specular atlas GL id, same fallback rule
    public static int getSpecularAtlas(int fallback) {
        return specularCount > 0 ? specularAtlas : fallback;
    }

    // Allocates both atlases at the base size and uploads every companion it can find
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
        } catch (Throwable t) {
            // PBR is an enhancement; a failure here must never break the atlas reload.
            LOGGER.error("[Umbra] Failed to build PBR atlases; packs will see flat surfaces", t);
            destroy();
        }
    }

    // Frees both GL textures, e.g. before a resource reload
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

    // Creates one atlas texture with every mip level filled with the neutral value
    private static int allocateAtlas(int width, int height, int mipmapLevels, int fillArgb) {
        int texture = TextureUtil.glGenTextures();
        TextureUtil.allocateTextureImpl(texture, mipmapLevels, width, height);

        // Fill level 0 (and mips) with the neutral value so unmapped sprites read as flat/non-reflective.
        GlStateManager.bindTexture(texture);
        TextureUtil.uploadTextureMipmap(neutralMipLevels(width, height, mipmapLevels, fillArgb),
                width, height, 0, 0, false, false);
        return texture;
    }

    // Pins the PBR atlas sampler to nearest filtering plus clamp-to-edge, which is what Iris does in
    // PBRAtlasTexture#upload via getSamplerCache().getClampToEdge(FilterMode.NEAREST)
    //
    // It has to be re-applied because the uploads leave something else behind: TextureUtil.uploadTextureMipmap
    // applies its own sampling on every call, and this class passes blur=false, clamp=false. Through
    // setTextureBlurMipmap(false, true) that gives magnification NEAREST — already correct — but minification
    // NEAREST_MIPMAP_LINEAR, and setTextureClamped(false) leaves wrapping at REPEAT. The magnification filter was
    // never the problem; the other two are
    //
    // NEAREST_MIPMAP_LINEAR interpolates BETWEEN MIP LEVELS. These atlases hold labPBR channels rather than colour,
    // and in labPBR the specular alpha channel IS emissiveness. Coarser mips average neighbouring sprites together,
    // so a sprite with no _s companion — which should read the neutral fill and never glow — starts blending in its
    // atlas neighbours' emission as soon as the sampler drops to a coarser level
    // Mip level is chosen from screen-space UV derivatives, so it changes with VIEWING ANGLE: the glow appears when
    // the camera turns and vanishes when it turns back, while the draw call itself is byte-for-byte identical.
    // That is the failure servers hit when their scenery is built out of custom item models
    //
    // CLAMP_TO_EDGE matters for the neighbouring reason: a UV a hair past a sprite's edge must clamp inside that
    // sprite rather than wrap around to the far side of the atlas
    private static void applyPbrSampling(int mipmapLevels) {
        // Nearest in both directions. With mipmaps present minification must be NEAREST_MIPMAP_NEAREST, not plain
        // NEAREST_MIPMAP_LINEAR — the "_LINEAR" half is the level blend, and that is the whole bug.
        GlStateManager.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GlStateManager.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                mipmapLevels > 0 ? GL_NEAREST_MIPMAP_NEAREST : GL_NEAREST);
        GlStateManager.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GlStateManager.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    // One flat array per mip level, halving each time
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

    // Loads a companion, mipmaps it and copies it into the sprite's rect; false when the file is absent
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

    // Scales the companion image to the sprite's base resolution and crops to the first animation frame
    // Nearest-neighbour scaling specifically: companion maps routinely ship at a different resolution than the base
    // texture, and any smoothing filter would blend LabPBR's ENCODED channel values into each other — averaging two
    // material ids gives a third, unrelated material rather than something in between
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

    // Resolves <name>_n or _s next to the sprite; null when missing
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
