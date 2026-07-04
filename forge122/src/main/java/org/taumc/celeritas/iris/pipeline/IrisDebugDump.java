package org.taumc.celeritas.iris.pipeline;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.taumc.celeritas.iris.gl.framebuffer.IrisFramebuffer;
import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * One-shot render-target dumper: writes the pipeline's buffers as PNGs to {@code <gameDir>/celeritas_debug/} a few
 * seconds after a pack loads, so "which buffer went wrong" is answered by looking at images instead of theorizing.
 * Debug-only tooling; the GPU stall and allocation spike are irrelevant for a once-per-pack-load dump.
 */
public final class IrisDebugDump {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Iris");

    private IrisDebugDump() {
    }

    /** Reads an RGBA color texture through a scratch FBO and writes it as PNG. */
    public static void dumpColorTexture(String name, int texture, int width, int height) {
        IrisFramebuffer scratch = new IrisFramebuffer();
        try {
            scratch.addColorAttachment(0, texture);
            scratch.readBuffer(0);
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
            LWJGL.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            writePng(name, pixels, width, height, false);
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to dump {}", name, e);
        } finally {
            scratch.destroy();
        }
    }

    /** Reads a depth texture through a scratch FBO and writes a contrast-enhanced grayscale PNG. */
    public static void dumpDepthTexture(String name, int texture, int width, int height) {
        IrisFramebuffer scratch = new IrisFramebuffer();
        try {
            scratch.addDepthAttachment(texture);
            scratch.noDrawBuffers();
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
            LWJGL.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixels);
            writePng(name, pixels, width, height, true);
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to dump {}", name, e);
        } finally {
            scratch.destroy();
        }
    }

    /** Writes transformed GLSL (or any text) into the debug directory, so driver-visible sources are inspectable. */
    public static void dumpText(String name, String content) {
        try {
            File dir = new File(Minecraft.getMinecraft().gameDir, "celeritas_debug");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            java.nio.file.Files.write(new File(dir, name).toPath(),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to dump text {}", name, e);
        }
    }

    private static void writePng(String name, ByteBuffer pixels, int width, int height, boolean depth) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            int srcRow = (height - 1 - y) * width; // GL reads bottom-up
            for (int x = 0; x < width; x++) {
                int rgb;
                if (depth) {
                    float d = pixels.getFloat((srcRow + x) * 4);
                    // Contrast-enhance: terrain depth clusters near 1.0; show near geometry bright.
                    int v = Math.min(255, Math.max(0, (int) ((1.0f - d) * 2000.0f)));
                    rgb = (v << 16) | (v << 8) | v;
                } else {
                    int base = (srcRow + x) * 4;
                    rgb = ((pixels.get(base) & 0xFF) << 16)
                            | ((pixels.get(base + 1) & 0xFF) << 8)
                            | (pixels.get(base + 2) & 0xFF);
                }
                image.setRGB(x, y, rgb);
            }
        }
        File dir = new File(Minecraft.getMinecraft().gameDir, "celeritas_debug");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create " + dir);
        }
        File out = new File(dir, name + ".png");
        ImageIO.write(image, "png", out);
        LOGGER.info("[Iris] Dumped {} ({}x{})", out.getAbsolutePath(), width, height);
    }
}
