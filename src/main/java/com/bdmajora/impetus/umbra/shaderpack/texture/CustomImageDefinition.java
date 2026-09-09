package com.bdmajora.impetus.umbra.shaderpack.texture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// One parsed `image.<name>` directive, whose value is
//   <samplerName> <format> <internalFormat> <pixelType> <clear> <relative> <sizeX> <sizeY> [<sizeZ>]
// These are Iris's custom WRITABLE images, reached from a shader through imageStore and imageLoad — packs use them
// for voxelization, which is how Complementary's coloured lighting works
// Free of Minecraft and GL: this is parsing only, and CustomImageManager resolves the format strings and owns the
// actual GL objects
public final class CustomImageDefinition {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    public final String name;
    public final String samplerName;
    public final String format;
    public final String internalFormat;
    public final String pixelType;
    // Whether the image is zeroed at the start of every frame. A pack that accumulates across frames declares this
    // false, and clearing it anyway would wipe its history every frame
    public final boolean clear;
    // True when the declared sizes are viewport-relative multipliers rather than absolute texels, in which case
    // the real dimensions come from relativeX/relativeY times the render size
    public final boolean relative;
    // Fixed dimensions in texels. For a relative image these are 0 and the manager derives the real size instead
    public final int sizeX;
    public final int sizeY;
    // 0 for a 2D image. A relative image is always 2D, matching Iris's GlImage.Relative — there is no meaningful
    // viewport-relative depth
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

    // Parses one directive value, returning null with a log line when it is malformed
    // Dropping a directive here is never harmless, which is why the log line spells out the consequence
    // The image is not created, so its sampler uniform is never assigned a texture unit and keeps GLSL's default of
    // 0 — shared with whatever the pipeline binds there. That is usually a DIFFERENT sampler type, since unit 0
    // holds a sampler2D while these images are typically usampler2D or usampler3D
    // Under the GL spec two sampler types on one unit makes the whole program invalid, and every draw using it
    // fails with GL_INVALID_OPERATION. NVIDIA ignores that rule and Mesa enforces it, so the visible symptom is a
    // pass that works on one machine and dies on another, reported as a bare GL error 0x502 that names nothing
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

    // Names the SAMPLER that is about to be left unbound, not just the directive that failed — the sampler name is
    // what the resulting GL error will not tell you
    // Recoverable from parts[0] in every malformed form this method rejects except a completely empty value
    private static void warnMalformed(String name, String value, String[] parts) {
        String samplerName = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "(unknown)";
        LOGGER.error("[Umbra] Malformed image directive image.{} = {} — the image is NOT created and sampler '{}' is "
                        + "left on texture unit 0, which fails every draw in any program that declares it with "
                        + "GL_INVALID_OPERATION on a strict driver",
                name, value, samplerName);
    }
}
