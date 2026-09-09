package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.umbra.gl.GlTextureUnits;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.umbra.shaderpack.texture.CustomImageDefinition;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL12;
import com.bdmajora.impetus.lwjgl.GL13;
import com.bdmajora.impetus.lwjgl.GL15;
import com.bdmajora.impetus.lwjgl.GL30;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Owns the pack's writable custom images ({@code image.*} directives, Umbra's imageStore/imageLoad extension) —
 * Complementary's colored-lighting voxel/floodfill volumes. Each image gets a GL 4.2 image unit (declaration order,
 * bound READ_WRITE every frame) and its paired sampler name a dedicated texture unit, so programs address both by
 * plain {@code glUniform1i} like every other sampler in this pipeline.
 * <p>
 * Image contents persist exactly as the shaderpack declares. Complementary's colored-lighting floodfill ping-pongs
 * through 3D images, so forcing extra clears destroys the history buffer every other frame.
 */
public class CustomImageManager {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");
    private static final int MAX_DECLARED_IMAGES = 16;
    /** Well above GlStateManager's eight cached units, so a raw bind here cannot desync it. */
    private static final int RESIZE_SCRATCH_UNIT = 32;
    private static final int GL_MAX_IMAGE_UNITS = 0x8D57;
    private static final int GL_TEXTURE_MIN_LOD = 0x813A;
    private static final int GL_TEXTURE_MAX_LOD = 0x813B;
    private static final int GL_TEXTURE_MAX_LEVEL = 0x813D;
    private static final int GL_TEXTURE_LOD_BIAS = 0x8501;
    private static final int GL_TEXTURE_FETCH_BARRIER_BIT = 0x00000008;
    private static final int GL_SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x00000020;
    private static final int GL_TEXTURE_UPDATE_BARRIER_BIT = 0x00000100;
    private static final int CLEAR_VISIBILITY_BARRIERS =
            GL_TEXTURE_UPDATE_BARRIER_BIT
                    | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GL_TEXTURE_FETCH_BARRIER_BIT;

    private static final class Image {
        final CustomImageDefinition definition;
        final int texture;
        final int target;
        final int imageUnit;
        final int samplerUnit;
        final int glInternalFormat;
        final int glFormat;
        final int glPixelType;

        Image(CustomImageDefinition definition, int texture, int target, int imageUnit, int samplerUnit,
              int glInternalFormat, int glFormat, int glPixelType) {
            this.definition = definition;
            this.texture = texture;
            this.target = target;
            this.imageUnit = imageUnit;
            this.samplerUnit = samplerUnit;
            this.glInternalFormat = glInternalFormat;
            this.glFormat = glFormat;
            this.glPixelType = glPixelType;
        }
    }

    private final List<Image> images = new ArrayList<>();
    /** Image uniform name → image unit AND sampler name → texture unit, for program uniform assignment. */
    private final Map<String, Integer> uniformOverrides = new LinkedHashMap<>();
    /** The driver's {@code GL_MAX_IMAGE_UNITS}; the ceiling the render-target images allocate up to. */
    private final int hardwareImageUnits;
    /** Current render size, for the viewport-relative images. */
    private int renderWidth;
    private int renderHeight;

    public CustomImageManager(List<CustomImageDefinition> definitions, int firstSamplerUnit, int lastSamplerUnit,
                              int renderWidth, int renderHeight) {
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        int reportedImageUnits = LWJGL.glGetInteger(GL_MAX_IMAGE_UNITS);
        this.hardwareImageUnits = reportedImageUnits > 0 ? reportedImageUnits : MAX_DECLARED_IMAGES;
        int imageUnitLimit = Math.min(MAX_DECLARED_IMAGES, this.hardwareImageUnits);
        int nextSamplerUnit = firstSamplerUnit;
        for (CustomImageDefinition definition : definitions) {
            if (this.images.size() >= imageUnitLimit) {
                LOGGER.error("[Umbra] Out of image units for image.{} (max {}); ignoring it — sampler '{}' stays on "
                                + "texture unit 0 and will fail every draw in any program declaring it with "
                                + "GL_INVALID_OPERATION on a strict driver",
                        definition.name, imageUnitLimit, definition.samplerName);
                continue;
            }
            int internalFormat = glInternalFormat(definition.internalFormat);
            int format = glFormat(definition.format);
            int pixelType = glPixelType(definition.pixelType);
            if (internalFormat == 0 || format == 0 || pixelType == 0) {
                LOGGER.error("[Umbra] Unsupported format for image.{} ({} {} {}); ignoring it — sampler '{}' stays on "
                                + "texture unit 0 and will fail every draw in any program declaring it with "
                                + "GL_INVALID_OPERATION on a strict driver",
                        definition.name, definition.format, definition.internalFormat, definition.pixelType,
                        definition.samplerName);
                continue;
            }

            boolean is3D = definition.sizeZ > 0;
            int target = is3D ? GL12.GL_TEXTURE_3D : GL11.GL_TEXTURE_2D;
            // Integer texture formats must sample NEAREST; float formats get LINEAR for smooth floodfill lookups.
            int filter = isIntegerFormat(definition.internalFormat) ? GL11.GL_NEAREST : GL11.GL_LINEAR;
            int sizeX = relativeSizeX(definition);
            int sizeY = relativeSizeY(definition);

            int texture = LWJGL.glGenTextures();
            LWJGL.glBindTexture(target, texture);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_MIN_FILTER, filter);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_MAG_FILTER, filter);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // Umbra' GlImage pins custom images to mip level 0. Letting a 3D floodfill texture inherit any mip/LOD
            // state makes sampler3D lookups read undefined levels, which shows up as every-other-frame colored noise.
            LWJGL.glTexParameteri(target, GL_TEXTURE_MAX_LEVEL, 0);
            LWJGL.glTexParameteri(target, GL_TEXTURE_MIN_LOD, 0);
            LWJGL.glTexParameteri(target, GL_TEXTURE_MAX_LOD, 0);
            LWJGL.glTexParameterf(target, GL_TEXTURE_LOD_BIAS, 0.0f);
            if (is3D) {
                LWJGL.glTexParameteri(target, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
                LWJGL.glTexImage3D(target, 0, internalFormat, sizeX, sizeY, definition.sizeZ,
                        0, format, pixelType, (ByteBuffer) null);
            } else {
                LWJGL.glTexImage2D(target, 0, internalFormat, sizeX, sizeY,
                        0, format, pixelType, (ByteBuffer) null);
            }
            LWJGL.glBindTexture(target, 0);
            // Zero-initialize regardless of the per-frame clear flag: glTexImage with null data is UNDEFINED memory,
            // and packs deliberately skip writing some texels (Complementary's behind-player floodfill optimization),
            // so creation-time garbage would otherwise survive — and flicker once the ping-pong alternates sides.
            clearTexture(texture, format, pixelType);

            int imageUnit = this.images.size();
            int samplerUnit = -1;
            if (definition.samplerName != null && !definition.samplerName.isEmpty()) {
                if (nextSamplerUnit <= lastSamplerUnit) {
                    samplerUnit = nextSamplerUnit++;
                } else {
                    LOGGER.error("[Umbra] Out of texture units for image sampler '{}' (last usable is {}); image.{} "
                                    + "remains writable on image unit {}, but the sampler stays on texture unit 0 "
                                    + "and will fail every draw in any program declaring it with "
                                    + "GL_INVALID_OPERATION on a strict driver. Per-program sampler allocation "
                                    + "(gl/program/ProgramSamplers) is what removes this shortage.",
                            definition.samplerName, lastSamplerUnit, definition.name, imageUnit);
                }
            }
            this.images.add(new Image(definition, texture, target, imageUnit, samplerUnit,
                    internalFormat, format, pixelType));
            this.uniformOverrides.put(definition.name, imageUnit);
            if (samplerUnit >= 0) {
                this.uniformOverrides.put(definition.samplerName, samplerUnit);
            }
        }
    }

    /**
     * Resolved through the shared render-target table rather than a local list, which is what Umbra does too
     * ({@code InternalTextureFormat.fromString}). The local list covered only the seven formats Complementary's
     * floodfill uses, so an SMAA pack asking for a perfectly ordinary {@code rg8}/{@code rgba16} edge buffer had its
     * images silently dropped.
     */
    private static int glInternalFormat(String name) {
        return com.bdmajora.impetus.umbra.gl.texture.InternalTextureFormat.fromString(name)
                .map(com.bdmajora.impetus.umbra.gl.texture.InternalTextureFormat::getInternalFormat)
                .orElse(0);
    }

    private static boolean isIntegerFormat(String name) {
        return com.bdmajora.impetus.umbra.gl.texture.InternalTextureFormat.fromString(name)
                .map(com.bdmajora.impetus.umbra.gl.texture.InternalTextureFormat::isInteger)
                .orElse(Boolean.FALSE);
    }

    private static int glFormat(String name) {
        switch (name) {
            case "red_integer": return GL30.GL_RED_INTEGER;
            case "rg_integer": return GL30.GL_RG_INTEGER;
            case "rgb_integer": return GL30.GL_RGB_INTEGER;
            case "rgba_integer": return GL30.GL_RGBA_INTEGER;
            case "red": return GL11.GL_RED;
            case "rg": return GL30.GL_RG;
            case "rgb": return GL11.GL_RGB;
            case "rgba": return GL11.GL_RGBA;
            default: return 0;
        }
    }

    private static int glPixelType(String name) {
        switch (name) {
            case "unsigned_int": return GL11.GL_UNSIGNED_INT;
            case "unsigned_short": return GL11.GL_UNSIGNED_SHORT;
            case "unsigned_byte": return GL11.GL_UNSIGNED_BYTE;
            case "int": return GL11.GL_INT;
            case "short": return GL11.GL_SHORT;
            case "byte": return GL11.GL_BYTE;
            case "half_float": return GL30.GL_HALF_FLOAT;
            case "float": return GL11.GL_FLOAT;
            default: return 0;
        }
    }

    private int relativeSizeX(CustomImageDefinition definition) {
        return definition.relative ? Math.max(1, (int) (this.renderWidth * definition.relativeX)) : definition.sizeX;
    }

    private int relativeSizeY(CustomImageDefinition definition) {
        return definition.relative ? Math.max(1, (int) (this.renderHeight * definition.relativeY)) : definition.sizeY;
    }

    /**
     * Re-allocates the viewport-relative images at the new render size (Umbra's {@code GlImage.Relative#updateNewSize}).
     * Contents are not preserved — the storage is reallocated — which matches Umbra and is what a screen-space image
     * wants anyway; {@link #bindAll} re-establishes every binding on the next frame regardless.
     * <p>
     * The reallocation happens on a scratch unit far above the eight {@code GlStateManager} caches, because a raw
     * bind on units 0-7 desynchronizes that cache and makes a later {@code bindTexture} there a silent no-op.
     */
    public void onResize(int width, int height) {
        if (width == this.renderWidth && height == this.renderHeight) {
            return;
        }
        this.renderWidth = width;
        this.renderHeight = height;
        boolean bound = false;
        for (Image image : this.images) {
            if (!image.definition.relative) {
                continue;
            }
            if (!bound) {
                GlTextureUnits.selectScratch(RESIZE_SCRATCH_UNIT);
                bound = true;
            }
            LWJGL.glBindTexture(image.target, image.texture);
            // Relative images are 2D by definition (Umbra's GlImage.Relative).
            LWJGL.glTexImage2D(image.target, 0, image.glInternalFormat,
                    relativeSizeX(image.definition), relativeSizeY(image.definition),
                    0, image.glFormat, image.glPixelType, (ByteBuffer) null);
            clearTexture(image.texture, image.glFormat, image.glPixelType);
        }
        if (bound) {
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GlTextureUnits.resetToUnit0();
        }
    }

    public boolean isEmpty() {
        return this.images.isEmpty();
    }

    /** Image uniform name → image unit plus sampler name → texture unit (both plain glUniform1i assignments). */
    public Map<String, Integer> getUniformOverrides() {
        return this.uniformOverrides;
    }

    /**
     * First image unit not taken by an {@code image.<name>} directive. The render-target images ({@code colorimgN},
     * Umbra's {@code UmbraImages.addRenderTargetImages}) are allocated upward from here.
     */
    public int getNextAvailableImageUnit() {
        return this.images.size();
    }

    /** The driver's {@code GL_MAX_IMAGE_UNITS}, i.e. the exclusive upper bound for any image unit. */
    public int getHardwareImageUnits() {
        return this.hardwareImageUnits;
    }

    /** The first 3D image's dimensions, used to derive the shadowcomp dispatch size (voxel volume / local size). */
    public int[] getFirst3DImageSize() {
        for (Image image : this.images) {
            if (image.definition.sizeZ > 0) {
                return new int[]{image.definition.sizeX, image.definition.sizeY, image.definition.sizeZ};
            }
        }
        return null;
    }

    /** Start-of-frame reset for the shaderpack-declared clearable images. */
    public void clearAll() {
        boolean cleared = false;
        for (Image image : this.images) {
            if (image.definition.clear) {
                clearTexture(image.texture, image.glFormat, image.glPixelType);
                cleared = true;
            }
        }
        if (cleared) {
            LWJGL.glMemoryBarrier(CLEAR_VISIBILITY_BARRIERS);
        }
    }

    private void clearTexture(int texture, int format, int pixelType) {
        // Umbra clears custom images with a null data pointer, which means "clear to zero" and avoids any
        // interaction with client memory or a currently-bound pixel-unpack buffer.
        LWJGL.glClearTexImage(texture, 0, format, pixelType);
    }

    /**
     * Binds every image on its image unit (READ_WRITE) and its texture on the paired sampler unit.
     * <p>
     * Each image is bound to exactly the texture the pack declared for it, and nothing here knows any pack-specific
     * uniform name — Umbra has no such concept. This previously aliased {@code floodfill_sampler_copy} onto
     * {@code floodfill_img} for visible programs. Complementary ping-pongs on {@code framemod2}: the compute writes
     * {@code floodfill_img_copy} on even frames and {@code floodfill_img} on odd ones, and {@code GetLightVolume}
     * reads back whichever was just written. Aliasing the two names onto one texture therefore pointed every
     * even-frame read at the volume that had *not* been updated.
     */
    public void bindAll() {
        for (Image image : this.images) {
            // Umbra binds custom images as layered for every texture target.
            LWJGL.glBindImageTexture(image.imageUnit, image.texture, 0, true, 0, GL15.GL_READ_WRITE,
                    image.glInternalFormat);
            if (image.samplerUnit >= 0) {
                GlTextureUnits.selectScratch(image.samplerUnit);
                LWJGL.glBindTexture(image.target, image.texture);
            }
        }
        GlTextureUnits.resetToUnit0();
    }

    public void unbindAll() {
        for (Image image : this.images) {
            if (image.samplerUnit >= 0) {
                GlTextureUnits.selectScratch(image.samplerUnit);
                LWJGL.glBindTexture(image.target, 0);
            }
        }
        GlTextureUnits.resetToUnit0();
    }

    public void destroy() {
        for (Image image : this.images) {
            LWJGL.glDeleteTextures(image.texture);
        }
        this.images.clear();
        this.uniformOverrides.clear();
    }

}
