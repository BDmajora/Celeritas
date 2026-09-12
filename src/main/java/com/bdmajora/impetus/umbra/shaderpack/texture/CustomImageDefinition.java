package com.bdmajora.impetus.umbra.shaderpack.texture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// One parsed `image.<name>` directive: <samplerName> <format> <internalFormat> <pixelType> <clear> <relative> <sizeX> <sizeY> [<sizeZ>], Iris's WRITABLE images reached via imageStore/imageLoad (Complementary's voxelization); parsing only, CustomImageManager owns the GL objects
public final class CustomImageDefinition {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    public final String name;
    public final String samplerName;
    public final String format;
    public final String internalFormat;
    public final String pixelType;
    // Whether the image is zeroed at frame start; a pack accumulating across frames declares false, and clearing anyway wipes its history
    public final boolean clear;
    // True when the declared sizes are viewport-relative multipliers rather than texels, the real dimensions being relativeX/relativeY times the render size
    public final boolean relative;
    // Fixed dimensions in texels. For a relative image these are 0 and the manager derives the real size instead
    public final int sizeX;
    public final int sizeY;
    // 0 for a 2D image; a relative image is always 2D like Iris's GlImage.Relative, since there is no meaningful viewport-relative depth
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

    // Parses one directive value, returning null with a log line when malformed; the consequence is spelled out because the missing image leaves its sampler on unit 0 with a DIFFERENT sampler type, which makes the program invalid (GL_INVALID_OPERATION) on Mesa but not NVIDIA, reported as a bare 0x502
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

    // Names the SAMPLER about to be left unbound, not just the failed directive, since that is what the resulting GL error will not tell you; recoverable from parts[0] except for an empty value
    private static void warnMalformed(String name, String value, String[] parts) {
        String samplerName = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "(unknown)";
        LOGGER.error("[Umbra] Malformed image directive image.{} = {} — the image is NOT created and sampler '{}' is "
                        + "left on texture unit 0, which fails every draw in any program that declares it with "
                        + "GL_INVALID_OPERATION on a strict driver",
                name, value, samplerName);
    }
}
