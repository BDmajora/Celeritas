package com.bdmajora.impetus.iris.shaderpack.texture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A parsed {@code image.<name> = <samplerName> <format> <internalFormat> <pixelType> <clear> <relative> <sizeX>
 * <sizeY> [<sizeZ>]} directive — Iris's custom writable images (imageStore/imageLoad), used by packs for
 * voxelization (Complementary's colored lighting). Minecraft/GL-free; {@code pipeline.CustomImageManager} resolves
 * the format strings and owns the GL objects.
 */
public final class CustomImageDefinition {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    public final String name;
    public final String samplerName;
    public final String format;
    public final String internalFormat;
    public final String pixelType;
    /** Whether the image is cleared to zero at the start of every frame. */
    public final boolean clear;
    /** True when the sizes are viewport-relative multipliers, held in {@link #relativeX}/{@link #relativeY}. */
    public final boolean relative;
    /** Fixed dimensions; for a relative image these are 0 and the manager derives them from the render size. */
    public final int sizeX;
    public final int sizeY;
    /** 0 for a 2D image. A relative image is always 2D (Iris's {@code GlImage.Relative}). */
    public final int sizeZ;
    public final float relativeX;
    public final float relativeY;

    private CustomImageDefinition(String name, String samplerName, String format, String internalFormat,
                                  String pixelType, boolean clear, boolean relative, int sizeX, int sizeY, int sizeZ,
                                  float relativeX, float relativeY) {
        this.name = name;
        this.samplerName = samplerName;
        this.format = format;
        this.internalFormat = internalFormat;
        this.pixelType = pixelType;
        this.clear = clear;
        this.relative = relative;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.relativeX = relativeX;
        this.relativeY = relativeY;
    }

    /**
     * Parses one directive value, returning {@code null} (with a log line) when malformed.
     * <p>
     * Dropping a directive is never harmless, which is why the log line spells the consequence out. The image is not
     * created, so its sampler uniform is never assigned a texture unit and keeps GLSL's default of <b>0</b> — shared
     * with whatever the pipeline binds there. If that is a different sampler type (it usually is: unit 0 holds a
     * {@code sampler2D} and these images are typically {@code usampler2D}/{@code usampler3D}), the GL spec makes the
     * whole program invalid and <em>every</em> draw using it fails with {@code GL_INVALID_OPERATION}. NVIDIA ignores
     * that rule and Mesa enforces it, so the visible result is a pass that works on one machine and dies on another,
     * reported as a bare {@code GL error 0x502} that names nothing.
     */
    public static CustomImageDefinition parse(String name, String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 8 || parts.length > 9) {
            warnMalformed(name, value, parts);
            return null;
        }
        try {
            boolean clear = Boolean.parseBoolean(parts[4]);
            if (Boolean.parseBoolean(parts[5])) {
                // Viewport-relative: the last two fields are float multipliers of the render size, not pixel counts.
                return new CustomImageDefinition(name, parts[0], parts[1], parts[2], parts[3],
                        clear, true, 0, 0, 0, Float.parseFloat(parts[6]), Float.parseFloat(parts[7]));
            }
            int sizeX = Integer.parseInt(parts[6]);
            int sizeY = Integer.parseInt(parts[7]);
            int sizeZ = parts.length == 9 ? Integer.parseInt(parts[8]) : 0;
            return new CustomImageDefinition(name, parts[0], parts[1], parts[2], parts[3],
                    clear, false, sizeX, sizeY, sizeZ, 0.0f, 0.0f);
        } catch (NumberFormatException e) {
            warnMalformed(name, value, parts);
            return null;
        }
    }

    /**
     * Names the sampler that is about to be left unbound, not just the directive that failed. The sampler name is the
     * first field, so it survives every malformed form this method rejects except an empty value.
     */
    private static void warnMalformed(String name, String value, String[] parts) {
        String samplerName = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "(unknown)";
        LOGGER.error("[Iris] Malformed image directive image.{} = {} — the image is NOT created and sampler '{}' is "
                        + "left on texture unit 0, which fails every draw in any program that declares it with "
                        + "GL_INVALID_OPERATION on a strict driver",
                name, value, samplerName);
    }
}
