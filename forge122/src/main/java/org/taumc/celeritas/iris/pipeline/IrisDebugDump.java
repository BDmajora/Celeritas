package org.taumc.celeritas.iris.pipeline;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.taumc.celeritas.iris.gl.framebuffer.IrisFramebuffer;
import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * One-shot render-target dumper: writes the pipeline's buffers as PNGs to {@code <gameDir>/celeritas_debug/} a few
 * seconds after a pack loads, so "which buffer went wrong" is answered by looking at images instead of theorizing.
 * Debug-only tooling; the GPU stall and allocation spike are irrelevant for a once-per-pack-load dump.
 */
public final class IrisDebugDump {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Iris");
    private static final String DEBUG_DIR_NAME = "celeritas_debug";
    private static final long MIB = 1024L * 1024L;
    private static final int MAX_DUMP_FILES =
            Integer.getInteger("celeritas.iris.debugDumpMaxFiles", 64);
    private static final long MAX_DUMP_BYTES =
            Long.getLong("celeritas.iris.debugDumpMaxBytes", 64L * MIB);
    private static final long MAX_RAW_IMAGE_BYTES =
            Long.getLong("celeritas.iris.debugDumpMaxImageBytes", 16L * MIB);
    private static boolean budgetWarningLogged;

    private IrisDebugDump() {
    }

    /** Reads an RGBA color texture through a scratch FBO and writes it as PNG. */
    public static void dumpColorTexture(String name, int texture, int width, int height) {
        long rawBytes = rawImageBytes(width, height);
        if (!canReadImage(name, width, height, rawBytes)) {
            return;
        }
        IrisFramebuffer scratch = new IrisFramebuffer();
        try {
            scratch.addColorAttachment(0, texture);
            scratch.readBuffer(0);
            ByteBuffer pixels = ByteBuffer.allocateDirect((int) rawBytes).order(ByteOrder.nativeOrder());
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
        long rawBytes = rawImageBytes(width, height);
        if (!canReadImage(name, width, height, rawBytes)) {
            return;
        }
        IrisFramebuffer scratch = new IrisFramebuffer();
        try {
            scratch.addDepthAttachment(texture);
            scratch.noDrawBuffers();
            ByteBuffer pixels = ByteBuffer.allocateDirect((int) rawBytes).order(ByteOrder.nativeOrder());
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
            File dir = debugDir();
            File out = new File(dir, name);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (!canWriteFile(dir, out, bytes.length)) {
                return;
            }
            Files.write(out.toPath(), bytes);
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
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", encoded)) {
            throw new IllegalStateException("No PNG writer available");
        }
        byte[] data = encoded.toByteArray();
        File dir = debugDir();
        File out = new File(dir, name + ".png");
        if (!canWriteFile(dir, out, data.length)) {
            return;
        }
        Files.write(out.toPath(), data);
        LOGGER.info("[Iris] Dumped {} ({}x{})", out.getAbsolutePath(), width, height);
    }

    private static File debugDir() {
        return new File(Minecraft.getMinecraft().gameDir, DEBUG_DIR_NAME);
    }

    private static long rawImageBytes(int width, int height) {
        if (width <= 0 || height <= 0) {
            return -1L;
        }
        return (long) width * (long) height * 4L;
    }

    private static boolean canReadImage(String name, int width, int height, long rawBytes) {
        if (rawBytes <= 0L || rawBytes > Integer.MAX_VALUE) {
            LOGGER.warn("[Iris] Skipping debug dump {} with invalid size {}x{}", name, width, height);
            return false;
        }
        if (MAX_RAW_IMAGE_BYTES >= 0L && rawBytes > MAX_RAW_IMAGE_BYTES) {
            warnBudgetOnce("raw image {}x{} would allocate {} MiB (limit {} MiB)",
                    width, height, rawBytes / MIB, MAX_RAW_IMAGE_BYTES / MIB);
            return false;
        }
        return true;
    }

    private static boolean canWriteFile(File dir, File out, long incomingBytes) {
        if (!ensureDebugDir(dir)) {
            return false;
        }
        DebugDirStats stats = scanDebugDir(dir);
        boolean existingFile = out.isFile();
        long existingBytes = existingFile ? out.length() : 0L;
        int projectedFiles = stats.files + (existingFile ? 0 : 1);
        long projectedBytes = stats.bytes - existingBytes + Math.max(0L, incomingBytes);

        if (MAX_DUMP_FILES >= 0 && projectedFiles > MAX_DUMP_FILES) {
            warnBudgetOnce("{} file(s) would exceed the {} file limit", projectedFiles, MAX_DUMP_FILES);
            return false;
        }
        if (MAX_DUMP_BYTES >= 0L && projectedBytes > MAX_DUMP_BYTES) {
            warnBudgetOnce("{} MiB would exceed the {} MiB limit", projectedBytes / MIB, MAX_DUMP_BYTES / MIB);
            return false;
        }
        return true;
    }

    private static boolean ensureDebugDir(File dir) {
        if (dir.isDirectory()) {
            return true;
        }
        if (dir.exists()) {
            LOGGER.warn("[Iris] Could not create debug dump directory because {} already exists", dir);
            return false;
        }
        if (!dir.mkdirs()) {
            LOGGER.warn("[Iris] Could not create debug dump directory {}", dir);
            return false;
        }
        return true;
    }

    private static DebugDirStats scanDebugDir(File dir) {
        DebugDirStats stats = new DebugDirStats();
        File[] files = dir.listFiles();
        if (files == null) {
            return stats;
        }
        for (File file : files) {
            if (file.isFile()) {
                stats.files++;
                stats.bytes += file.length();
            }
        }
        return stats;
    }

    private static void warnBudgetOnce(String format, Object... args) {
        if (budgetWarningLogged) {
            return;
        }
        budgetWarningLogged = true;
        LOGGER.warn("[Iris] celeritas_debug budget reached; skipping further debug dumps: " + format, args);
    }

    private static final class DebugDirStats {
        int files;
        long bytes;
    }
}
