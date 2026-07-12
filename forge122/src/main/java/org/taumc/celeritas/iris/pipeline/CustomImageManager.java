package org.taumc.celeritas.iris.pipeline;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.taumc.celeritas.iris.shaderpack.texture.CustomImageDefinition;
import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL12;
import org.taumc.celeritas.lwjgl.GL13;
import org.taumc.celeritas.lwjgl.GL15;
import org.taumc.celeritas.lwjgl.GL30;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Owns the pack's writable custom images ({@code image.*} directives, Iris's imageStore/imageLoad extension) —
 * Complementary's colored-lighting voxel/floodfill volumes. Each image gets a GL 4.2 image unit (declaration order,
 * bound READ_WRITE every frame) and its paired sampler name a dedicated texture unit, so programs address both by
 * plain {@code glUniform1i} like every other sampler in this pipeline.
 * <p>
 * Image contents persist exactly as the shaderpack declares. Complementary's colored-lighting floodfill ping-pongs
 * through 3D images, so forcing extra clears destroys the history buffer every other frame.
 */
public class CustomImageManager {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Iris");
    private static final int MAX_DECLARED_IMAGES = 16;
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
    private final ByteBuffer zeroClearValue = ByteBuffer.allocateDirect(16);

    public CustomImageManager(List<CustomImageDefinition> definitions, int firstSamplerUnit, int lastSamplerUnit) {
        int reportedImageUnits = LWJGL.glGetInteger(GL_MAX_IMAGE_UNITS);
        int imageUnitLimit = reportedImageUnits > 0
                ? Math.min(MAX_DECLARED_IMAGES, reportedImageUnits)
                : MAX_DECLARED_IMAGES;
        int nextSamplerUnit = firstSamplerUnit;
        for (CustomImageDefinition definition : definitions) {
            if (this.images.size() >= imageUnitLimit) {
                LOGGER.error("[Iris] Out of image units for image.{} (max {}); ignoring it",
                        definition.name, imageUnitLimit);
                continue;
            }
            int internalFormat = glInternalFormat(definition.internalFormat);
            int format = glFormat(definition.format);
            int pixelType = glPixelType(definition.pixelType);
            if (internalFormat == 0 || format == 0 || pixelType == 0) {
                LOGGER.warn("[Iris] Unsupported format for image.{} ({} {} {}); ignoring it",
                        definition.name, definition.format, definition.internalFormat, definition.pixelType);
                continue;
            }

            boolean is3D = definition.sizeZ > 0;
            int target = is3D ? GL12.GL_TEXTURE_3D : GL11.GL_TEXTURE_2D;
            // Integer texture formats must sample NEAREST; float formats get LINEAR for smooth floodfill lookups.
            int filter = definition.internalFormat.endsWith("i") ? GL11.GL_NEAREST : GL11.GL_LINEAR;

            int texture = LWJGL.glGenTextures();
            LWJGL.glBindTexture(target, texture);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_MIN_FILTER, filter);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_MAG_FILTER, filter);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            LWJGL.glTexParameteri(target, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // Iris' GlImage pins custom images to mip level 0. Letting a 3D floodfill texture inherit any mip/LOD
            // state makes sampler3D lookups read undefined levels, which shows up as every-other-frame colored noise.
            LWJGL.glTexParameteri(target, GL_TEXTURE_MAX_LEVEL, 0);
            LWJGL.glTexParameteri(target, GL_TEXTURE_MIN_LOD, 0);
            LWJGL.glTexParameteri(target, GL_TEXTURE_MAX_LOD, 0);
            LWJGL.glTexParameterf(target, GL_TEXTURE_LOD_BIAS, 0.0f);
            if (is3D) {
                LWJGL.glTexParameteri(target, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
                LWJGL.glTexImage3D(target, 0, internalFormat, definition.sizeX, definition.sizeY, definition.sizeZ,
                        0, format, pixelType, (ByteBuffer) null);
            } else {
                LWJGL.glTexImage2D(target, 0, internalFormat, definition.sizeX, definition.sizeY,
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
                    LOGGER.error("[Iris] Out of texture units for image sampler {}; image.{} remains writable on image unit {}",
                            definition.samplerName, definition.name, imageUnit);
                }
            }
            this.images.add(new Image(definition, texture, target, imageUnit, samplerUnit,
                    internalFormat, format, pixelType));
            this.uniformOverrides.put(definition.name, imageUnit);
            if (samplerUnit >= 0) {
                this.uniformOverrides.put(definition.samplerName, samplerUnit);
            }
            LOGGER.info("[Iris] Custom image '{}' ({}x{}x{} {}) on image unit {}{}",
                    definition.name, definition.sizeX, definition.sizeY, definition.sizeZ,
                    definition.internalFormat, imageUnit,
                    samplerUnit >= 0 ? ", sampler '" + definition.samplerName + "' on unit " + samplerUnit
                            : ", sampler '" + definition.samplerName + "' unbound");
        }
    }

    private static int glInternalFormat(String name) {
        switch (name) {
            case "r16ui": return GL30.GL_R16UI;
            case "r32ui": return GL30.GL_R32UI;
            case "r8ui": return GL30.GL_R8UI;
            case "rgba16f": return GL30.GL_RGBA16F;
            case "rgba32f": return GL30.GL_RGBA32F;
            case "rgba8": return GL11.GL_RGBA8;
            case "r32f": return GL30.GL_R32F;
            default: return 0;
        }
    }

    private static int glFormat(String name) {
        switch (name) {
            case "red_integer": return GL30.GL_RED_INTEGER;
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
            case "half_float": return GL30.GL_HALF_FLOAT;
            case "float": return GL11.GL_FLOAT;
            default: return 0;
        }
    }

    public boolean isEmpty() {
        return this.images.isEmpty();
    }

    /** Image uniform name → image unit plus sampler name → texture unit (both plain glUniform1i assignments). */
    public Map<String, Integer> getUniformOverrides() {
        return this.uniformOverrides;
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
        this.zeroClearValue.clear();
        LWJGL.glClearTexImage(texture, 0, format, pixelType, this.zeroClearValue);
    }

    /** Binds every image on its image unit (READ_WRITE) and its texture on the paired sampler unit. */
    public void bindAll() {
        for (Image image : this.images) {
            // Iris binds custom images as layered for every texture target.
            LWJGL.glBindImageTexture(image.imageUnit, image.texture, 0, true, 0, GL15.GL_READ_WRITE,
                    image.glInternalFormat);
            if (image.samplerUnit >= 0) {
                LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + image.samplerUnit);
                LWJGL.glBindTexture(image.target, image.texture);
            }
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public void unbindAll() {
        for (Image image : this.images) {
            if (image.samplerUnit >= 0) {
                LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + image.samplerUnit);
                LWJGL.glBindTexture(image.target, 0);
            }
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public void destroy() {
        for (Image image : this.images) {
            LWJGL.glDeleteTextures(image.texture);
        }
        this.images.clear();
        this.uniformOverrides.clear();
    }
}
