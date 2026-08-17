package com.bdmajora.impetus.iris.devtool;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.imageio.ImageIO;

/**
 * Writes the shadow map to a PNG when the player takes a screenshot, so what the sun actually sees can be compared
 * against what the player sees in the same frame.
 * <p>
 * Depth statistics cannot distinguish "the shadow map holds the terrain above the player" from "it holds only the walls
 * the player can see" — both are just a spread of depths with some fraction cleared. An image separates them at a
 * glance, which matters for light shafts: underground, every raymarch sample sits below solid rock, so the shafts can
 * only leak if the rock overhead is missing from the shadow map.
 * <p>
 * Requested from the screenshot hook and serviced by the next shadow pass, because the shadow textures are only
 * readable while that pass owns the framebuffer.
 */
public final class ShadowMapDump {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");
    private static final String DEBUG_DIR_NAME = "impetus_debug";

    /**
     * Off unless {@code -Dimpetus.iris.shadowDump=true}. This runs on the SCREENSHOT key, so with it enabled every
     * ordinary F2 pays for it: {@code readDepth} allocates a direct {@code ByteBuffer} and a {@code float[]} of
     * resolution² each (67 MB apiece at 4096²) and runs twice, then each {@code writeDepth} builds a full
     * {@code BufferedImage} of the same size and PNG-encodes 16.7 megapixels — roughly 400 MB of allocation and a
     * multi-second stall per keypress. It also perturbs GL state the shadow pass owns (see
     * {@code IrisShadowRenderer.dumpShadowMapIfRequested}: the readback leaves {@code GL_READ_FRAMEBUFFER} on the
     * shadow framebuffer and the depth attachment is swapped mid-flight), and the shadow pass wraps it in a
     * {@code catch (Throwable)} that silently absorbs an {@code OutOfMemoryError} while its {@code finally} restores
     * neither the viewport nor the framebuffer. A diagnostic must not be able to damage the frame it is diagnosing.
     */
    private static final boolean ENABLED = Boolean.getBoolean("impetus.iris.shadowDump");

    private static volatile boolean requested;

    private ShadowMapDump() {
    }

    /** Called from the screenshot hook; the dump happens on the next shadow pass. */
    public static void request() {
        if (!ENABLED) {
            return;
        }
        requested = true;
    }

    /** {@return whether a dump is pending, clearing the request} */
    public static boolean consumeRequest() {
        boolean pending = requested;
        requested = false;
        return pending;
    }

    /**
     * Writes one depth buffer as greyscale. Depths are normalized across the range actually occupied by geometry —
     * the pack compresses them into roughly [0.4, 0.6] via {@code gl_Position.z *= 0.2}, so a raw 0..1 mapping would
     * render as a flat grey field with no readable structure. Cleared texels (no geometry) are written as pure black
     * so empty regions are obvious.
     */
    public static void writeDepth(String name, float[] depth, int resolution) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float d : depth) {
            if (d >= 0.99999f) {
                continue;
            }
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        if (min > max) {
            LOGGER.warn("[Iris] Shadow map dump '{}' skipped: no geometry in the buffer at all", name);
            return;
        }
        float span = Math.max(1.0e-6f, max - min);

        BufferedImage image = new BufferedImage(resolution, resolution, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < resolution; y++) {
            // glReadPixels returns rows bottom-up; flip so the PNG reads the way the shadow camera sees it.
            int sourceRow = (resolution - 1 - y) * resolution;
            for (int x = 0; x < resolution; x++) {
                float d = depth[sourceRow + x];
                int rgb;
                if (d >= 0.99999f) {
                    rgb = 0;
                } else {
                    int v = Math.round(255.0f * (1.0f - (d - min) / span));
                    v = Math.max(1, Math.min(255, v));
                    rgb = (v << 16) | (v << 8) | v;
                }
                image.setRGB(x, y, rgb);
            }
        }
        write(name, image, min, max);
    }

    private static void write(String name, BufferedImage image, float min, float max) {
        try {
            File dir = new File(Minecraft.getMinecraft().gameDir, DEBUG_DIR_NAME);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                LOGGER.warn("[Iris] Could not create {} for the shadow map dump", dir);
                return;
            }
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
            File out = new File(dir, "shadowmap_" + name + "_" + stamp + ".png");
            ImageIO.write(image, "png", out);
            LOGGER.info("[Iris] Shadow map dumped: {} (occupied depth range {}..{}, black = no geometry)",
                    out.getName(), min, max);
        } catch (Exception e) {
            LOGGER.warn("[Iris] Shadow map dump failed", e);
        }
    }
}
