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
    private final Map<String, Image> imagesBySampler = new LinkedHashMap<>();
    /** Image uniform name → image unit AND sampler name → texture unit, for program uniform assignment. */
    private final Map<String, Integer> uniformOverrides = new LinkedHashMap<>();

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
                this.imagesBySampler.put(definition.samplerName, this.images.get(this.images.size() - 1));
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
        // Iris clears custom images with a null data pointer, which means "clear to zero" and avoids any
        // interaction with client memory or a currently-bound pixel-unpack buffer.
        LWJGL.glClearTexImage(texture, 0, format, pixelType);
    }

    /**
     * Binds every image on its image unit (READ_WRITE) and its texture on the paired sampler unit.
     *
     * @param stableVisibleFloodfill when true, visible graphics programs sample one floodfill history on both
     *                               floodfill sampler names. Compute dispatches pass false so the pack's native
     *                               ping-pong still reads and writes the two physical volumes.
     */
    public void bindAll(boolean stableVisibleFloodfill) {
        for (Image image : this.images) {
            // Iris binds custom images as layered for every texture target.
            LWJGL.glBindImageTexture(image.imageUnit, image.texture, 0, true, 0, GL15.GL_READ_WRITE,
                    image.glInternalFormat);
            if (image.samplerUnit >= 0) {
                LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + image.samplerUnit);
                LWJGL.glBindTexture(image.target, image.texture);
            }
        }
        if (stableVisibleFloodfill) {
            bindStableFloodfillReader();
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void bindStableFloodfillReader() {
        Image floodfill = this.imagesBySampler.get("floodfill_sampler");
        Image copy = this.imagesBySampler.get("floodfill_sampler_copy");
        if (floodfill == null || copy == null || floodfill.samplerUnit < 0 || copy.samplerUnit < 0) {
            return;
        }
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + floodfill.samplerUnit);
        LWJGL.glBindTexture(floodfill.target, floodfill.texture);
        LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + copy.samplerUnit);
        LWJGL.glBindTexture(copy.target, floodfill.texture);
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
        if (this.probeFramebuffer != 0) {
            LWJGL.glDeleteFramebuffers(this.probeFramebuffer);
            this.probeFramebuffer = 0;
        }
        this.images.clear();
        this.imagesBySampler.clear();
        this.uniformOverrides.clear();
    }

    // ------------------------------------------------------------------ flicker probe

    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    private static final int GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int GL_RED_INTEGER = 0x8D94;
    private static final int PROBE_WINDOW = 64;
    private int probeFramebuffer;
    private ByteBuffer probeReadback;
    /** Per volume: this probe frame's decoded window values, last frame's, and the one before (delta baselines). */
    private final Map<String, float[][]> probeHistory = new LinkedHashMap<>();
    /** The floodfill main volume's decoded values this frame, for the main-vs-copy pair delta. */
    private float[] probePairBaseline;

    /**
     * Flicker-probe readback: hashes a {@value #PROBE_WINDOW}² window of the three central Z slices of every 3D
     * image (the voxel/floodfill volumes are camera-centered, so this covers the geometry around the player). A
     * hash that alternates between consecutive probe frames while standing still pinpoints which volume's CONTENT
     * is unstable, separating voxelization bugs from compute-scheduling and sampler-side bugs.
     */
    public void logProbeHashes(String suffix) {
        int previousReadFbo = LWJGL.glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        try {
            if (this.probeFramebuffer == 0) {
                this.probeFramebuffer = LWJGL.glGenFramebuffers();
            }
            LWJGL.glBindFramebuffer(GL_READ_FRAMEBUFFER, this.probeFramebuffer);
            for (Image image : this.images) {
                if (image.definition.sizeZ <= 0) {
                    continue;
                }
                int w = Math.min(PROBE_WINDOW, image.definition.sizeX);
                int h = Math.min(PROBE_WINDOW, image.definition.sizeY);
                int x0 = (image.definition.sizeX - w) / 2;
                int y0 = (image.definition.sizeY - h) / 2;
                boolean integerFormat = image.definition.internalFormat.endsWith("i");
                // Integer volumes (single-channel voxel ids) read back as R32UI; float volumes as full RGBA floats
                // so alternation in any light channel is caught.
                int format = integerFormat ? GL_RED_INTEGER : GL11.GL_RGBA;
                int type = integerFormat ? GL11.GL_UNSIGNED_INT : GL11.GL_FLOAT;
                int bytesNeeded = w * h * (integerFormat ? 4 : 16);
                if (this.probeReadback == null || this.probeReadback.capacity() < bytesNeeded) {
                    // Native byte order: glReadPixels writes native-endian data, and Java ByteBuffers default to
                    // big-endian — without this the decoded floats are byte-swapped garbage.
                    this.probeReadback = ByteBuffer.allocateDirect(bytesNeeded)
                            .order(java.nio.ByteOrder.nativeOrder());
                }
                long hash = 0xcbf29ce484222325L; // FNV-1a
                int centerZ = image.definition.sizeZ / 2;
                boolean ok = true;
                int valuesPerLayer = w * h * (integerFormat ? 1 : 4);
                float[] values = new float[valuesPerLayer * 3];
                int layerIndex = 0;
                for (int layer = centerZ - 1; layer <= centerZ + 1; layer++, layerIndex++) {
                    LWJGL.glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                            image.texture, 0, layer);
                    if (LWJGL.glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                        ok = false;
                        break;
                    }
                    LWJGL.glReadBuffer(GL_COLOR_ATTACHMENT0);
                    this.probeReadback.clear();
                    LWJGL.glReadPixels(x0, y0, w, h, format, type, this.probeReadback);
                    for (int i = 0; i < bytesNeeded; i++) {
                        hash = (hash ^ (this.probeReadback.get(i) & 0xFF)) * 0x100000001b3L;
                    }
                    for (int i = 0; i < valuesPerLayer; i++) {
                        values[layerIndex * valuesPerLayer + i] = integerFormat
                                ? (float) (this.probeReadback.getInt(i * 4) & 0xFFFFFFFFL)
                                : this.probeReadback.getFloat(i * 4);
                    }
                }
                LOGGER.info("[Iris] Flicker probe {} volume '{}': hash={} window={}x{}x3@z{} {}",
                        suffix, image.definition.name, ok ? Long.toHexString(hash) : "READBACK_INCOMPLETE",
                        w, h, centerZ, image.definition.internalFormat);
                if (ok) {
                    logProbeDeltas(suffix, image.definition.name, values);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[Iris] Flicker probe volume readback failed: {}", t.toString());
        } finally {
            LWJGL.glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFbo);
        }
    }

    /**
     * Numeric convergence/oscillation stats for one probed volume window. delta1 = mean |now − last probe frame|,
     * delta2 = mean |now − two probe frames ago|. A converging floodfill shows delta2 → 0; a frame-parity
     * oscillation shows delta1 large while delta2 ≈ 0. pairDelta (floodfill copy vs main, same frame) is the exact
     * field difference a parity-alternating reader flips between on screen.
     */
    private void logProbeDeltas(String suffix, String name, float[] values) {
        float[][] history = this.probeHistory.get(name);
        if (history == null) {
            history = new float[2][];
            this.probeHistory.put(name, history);
        }
        String stats = "meanAbs=" + meanAbs(values)
                + " delta1=" + meanAbsDelta(values, history[0])
                + " delta2=" + meanAbsDelta(values, history[1]);
        if (name.equals("floodfill_img")) {
            this.probePairBaseline = values;
        } else if (name.equals("floodfill_img_copy")) {
            stats += " pairDeltaVsMain=" + meanAbsDelta(values, this.probePairBaseline);
        }
        LOGGER.info("[Iris] Flicker probe {} volume '{}' stats: {}", suffix, name, stats);
        history[1] = history[0];
        history[0] = values;
    }

    private static String meanAbs(float[] values) {
        double sum = 0.0;
        for (float value : values) {
            sum += Math.abs(value);
        }
        return String.format("%.6g", sum / values.length);
    }

    private static String meanAbsDelta(float[] now, float[] before) {
        if (before == null || before.length != now.length) {
            return "n/a";
        }
        double sum = 0.0;
        double max = 0.0;
        for (int i = 0; i < now.length; i++) {
            double d = Math.abs(now[i] - before[i]);
            sum += d;
            max = Math.max(max, d);
        }
        return String.format("%.6g(max %.6g)", sum / now.length, max);
    }
}
