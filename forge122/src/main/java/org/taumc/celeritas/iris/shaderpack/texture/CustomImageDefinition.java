package org.taumc.celeritas.iris.shaderpack.texture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A parsed {@code image.<name> = <samplerName> <format> <internalFormat> <pixelType> <clear> <relative> <sizeX>
 * <sizeY> [<sizeZ>]} directive — Iris's custom writable images (imageStore/imageLoad), used by packs for
 * voxelization (Complementary's colored lighting). Minecraft/GL-free; {@code pipeline.CustomImageManager} resolves
 * the format strings and owns the GL objects.
 */
public final class CustomImageDefinition {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Iris");

    public final String name;
    public final String samplerName;
    public final String format;
    public final String internalFormat;
    public final String pixelType;
    /** Whether the image is cleared to zero at the start of every frame. */
    public final boolean clear;
    /** True when the sizes are viewport-relative floats; only fixed sizes are supported here. */
    public final boolean relative;
    public final int sizeX;
    public final int sizeY;
    /** 0 for a 2D image. */
    public final int sizeZ;

    private CustomImageDefinition(String name, String samplerName, String format, String internalFormat,
                                  String pixelType, boolean clear, boolean relative, int sizeX, int sizeY, int sizeZ) {
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
    }

    /** Parses one directive value, returning {@code null} (with a log line) when malformed or viewport-relative. */
    public static CustomImageDefinition parse(String name, String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 8 || parts.length > 9) {
            LOGGER.warn("[Iris] Malformed image directive image.{} = {}", name, value);
            return null;
        }
        try {
            boolean clear = Boolean.parseBoolean(parts[4]);
            boolean relative = Boolean.parseBoolean(parts[5]);
            if (relative) {
                LOGGER.warn("[Iris] Viewport-relative custom images are not supported yet, ignoring image.{}", name);
                return null;
            }
            int sizeX = Integer.parseInt(parts[6]);
            int sizeY = Integer.parseInt(parts[7]);
            int sizeZ = parts.length == 9 ? Integer.parseInt(parts[8]) : 0;
            return new CustomImageDefinition(name, parts[0], parts[1], parts[2], parts[3],
                    clear, false, sizeX, sizeY, sizeZ);
        } catch (NumberFormatException e) {
            LOGGER.warn("[Iris] Malformed image directive image.{} = {}", name, value);
            return null;
        }
    }
}
