package com.bdmajora.impetus.iris.pipeline;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.gl.framebuffer.IrisFramebuffer;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * One-shot per-pass readback probe: on a single frame shortly after pack load, logs mean RGBA (plus black-pixel
 * fraction and channel maximum) of what each deferred/composite pass just wrote, and depth-buffer statistics at the
 * stage boundaries. Answers "which pass first produces black" from one normal game run, where source dumps and GL
 * error probes say nothing. {@code -Dimpetus.iris.passTapFrame=N} moves the tap frame; {@code 0} disables it.
 */
final class IrisPassTap {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");
    private static ByteBuffer readback;

    private IrisPassTap() {
    }

    /**
     * Reads the given color attachment of the CURRENTLY BOUND framebuffer and logs its statistics. Pass
     * {@code attachment < 0} to read whatever read-buffer is already configured (the default framebuffer / screen).
     * Restores {@code GL_COLOR_ATTACHMENT0} as the read buffer afterwards, matching every pass FBO's baked state.
     */
    static void logColor(String label, int attachment, int width, int height) {
        try {
            if (attachment >= 0) {
                LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0 + attachment);
            }
            logStats(label, readPixels(width, height), width, height);
            if (attachment >= 0) {
                LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            }
        } catch (Throwable t) {
            LOGGER.warn("[Iris] PassTap {} failed: {}", label, t.toString());
        }
    }

    /**
     * Reads a color texture through a scratch FBO and logs its statistics. Unlike {@link #logColor}, the result depends
     * ONLY on the texture id, so it cannot be fooled by whatever attachment layout the last gbuffer program left on the
     * shared FBO ({@code retainColorAttachments} reshuffles those per program, which made the old
     * "pre-composite gbuffer colortex4" tap silently report colortex0's fog clear instead).
     */
    static void logColorTexture(String label, int colorTexture, int width, int height) {
        IrisFramebuffer scratch = new IrisFramebuffer();
        try {
            scratch.addColorAttachment(0, 0, colorTexture);
            scratch.bindAsReadBuffer();
            LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            logStats(label, readPixels(width, height), width, height);
        } catch (Throwable t) {
            LOGGER.warn("[Iris] PassTap {} failed: {}", label, t.toString());
        } finally {
            scratch.destroy();
        }
    }

    /** {@link #logGrid} against a texture id, via a scratch FBO. See {@link #logColorTexture}. */
    static void logGridTexture(String label, int colorTexture, int width, int height) {
        IrisFramebuffer scratch = new IrisFramebuffer();
        try {
            scratch.addColorAttachment(0, 0, colorTexture);
            scratch.bindAsReadBuffer();
            LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            logGridOf(label, readPixels(width, height), width, height);
        } catch (Throwable t) {
            LOGGER.warn("[Iris] PassTap GRID {} failed: {}", label, t.toString());
        } finally {
            scratch.destroy();
        }
    }

    private static ByteBuffer readPixels(int width, int height) {
        ByteBuffer pixels = buffer(width * height * 4);
        LWJGL.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static void logStats(String label, ByteBuffer pixels, int width, int height) {
        long r = 0;
        long g = 0;
        long b = 0;
        long a = 0;
        int black = 0;
        int max = 0;
        // Uniform buffers are the tell for a bogus readback, so also report how many distinct RGB values appear.
        int distinct = 0;
        int firstRgb = -1;
        int count = width * height;
        for (int i = 0; i < count; i++) {
            int base = i * 4;
            int pr = pixels.get(base) & 0xFF;
            int pg = pixels.get(base + 1) & 0xFF;
            int pb = pixels.get(base + 2) & 0xFF;
            r += pr;
            g += pg;
            b += pb;
            a += pixels.get(base + 3) & 0xFF;
            if (pr == 0 && pg == 0 && pb == 0) {
                black++;
            }
            max = Math.max(max, Math.max(pr, Math.max(pg, pb)));
            int rgb = (pr << 16) | (pg << 8) | pb;
            if (firstRgb < 0) {
                firstRgb = rgb;
                distinct = 1;
            } else if (distinct == 1 && rgb != firstRgb) {
                distinct = 2;
            }
        }
        LOGGER.info(String.format(Locale.ROOT,
                "[Iris] PassTap %s: mean=(%.2f, %.2f, %.2f, %.2f) black=%.1f%% maxChannel=%d uniform=%s",
                label, r / (double) count, g / (double) count, b / (double) count, a / (double) count,
                black * 100.0 / count, max, distinct == 1 ? "YES(SUSPECT)" : "no"));
    }

    /**
     * True when the depth texture holds at least one fragment nearer than the far plane, i.e. the frame actually
     * rasterized world geometry.
     * <p>
     * The tap fires on a fixed frame number, which on a fresh world lands while chunks are still building: every
     * buffer reads back at its clear value and every probe in the capture is meaningless. Worse, it is meaningless in
     * a way that looks like a finding — an empty depthtex0 reads exactly like a broken depth copy, and a colortex at
     * its clear colour reads exactly like a pass that wrote nothing. Gate the capture on this so the log only ever
     * contains frames with something in them.
     */
    static boolean hasGeometry(int depthTexture, int width, int height) {
        IrisFramebuffer scratch = new IrisFramebuffer();
        try {
            scratch.addDepthAttachment(depthTexture);
            scratch.noDrawBuffers();
            ByteBuffer pixels = buffer(width * height * 4);
            LWJGL.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixels);
            FloatBuffer depths = pixels.asFloatBuffer();
            int count = width * height;
            for (int i = 0; i < count; i++) {
                if (depths.get(i) < 1.0f) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            LOGGER.warn("[Iris] PassTap geometry check failed: {}", t.toString());
            // Do not suppress the capture because the check itself broke.
            return true;
        } finally {
            scratch.destroy();
        }
    }

    /** Reads a depth texture through a scratch FBO and logs mean/min plus the fraction of pixels at exactly 1.0. */
    static void logDepth(String label, int depthTexture, int width, int height) {
        IrisFramebuffer scratch = new IrisFramebuffer();
        try {
            scratch.addDepthAttachment(depthTexture);
            scratch.noDrawBuffers();
            ByteBuffer pixels = buffer(width * height * 4);
            LWJGL.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixels);
            FloatBuffer depths = pixels.asFloatBuffer();
            double sum = 0.0;
            float min = Float.POSITIVE_INFINITY;
            int atOne = 0;
            int count = width * height;
            for (int i = 0; i < count; i++) {
                float d = depths.get(i);
                sum += d;
                min = Math.min(min, d);
                if (d >= 1.0f) {
                    atOne++;
                }
            }
            LOGGER.info(String.format(Locale.ROOT,
                    "[Iris] PassTap %s: depth mean=%.6f min=%.6f atOne=%.1f%%",
                    label, sum / count, min, atOne * 100.0 / count));
        } catch (Throwable t) {
            LOGGER.warn("[Iris] PassTap {} failed: {}", label, t.toString());
        } finally {
            scratch.destroy();
        }
    }

    /**
     * Reads the given color attachment and logs a coarse ASCII brightness grid (cols x rows tiles, each the mean
     * luminance of its block), so spatial structure — smears, radial fans, checkerboards — is visible in the log
     * where a single mean RGBA cannot show it. {@code attachment < 0} reads the current read-buffer (screen).
     * Row 0 is the TOP of the image. Characters ramp dark->bright: ' .:-=+*#%@'.
     */
    static void logGrid(String label, int attachment, int width, int height) {
        try {
            if (attachment >= 0) {
                LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0 + attachment);
            }
            ByteBuffer pixels = readPixels(width, height);
            if (attachment >= 0) {
                LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            }
            logGridOf(label, pixels, width, height);
        } catch (Throwable t) {
            LOGGER.warn("[Iris] PassTap GRID {} failed: {}", label, t.toString());
        }
    }

    private static void logGridOf(String label, ByteBuffer pixels, int width, int height) {
        final int cols = 48;
        final int rows = 24;
        final String ramp = " .:-=+*#%@";
        try {
            LOGGER.info("[Iris] PassTap GRID {} ({}x{} tiles, top row first):", label, cols, rows);
            for (int ty = 0; ty < rows; ty++) {
                // glReadPixels origin is bottom-left; emit top row first so the log reads like the screen.
                int y0 = height - 1 - (ty * height) / rows;
                int y1 = height - 1 - ((ty + 1) * height) / rows;
                StringBuilder line = new StringBuilder(cols);
                for (int tx = 0; tx < cols; tx++) {
                    int x0 = (tx * width) / cols;
                    int x1 = ((tx + 1) * width) / cols;
                    long sum = 0;
                    int n = 0;
                    for (int y = y1 + 1; y <= y0; y++) {
                        int rowBase = y * width * 4;
                        for (int x = x0; x < x1; x++) {
                            int base = rowBase + x * 4;
                            int pr = pixels.get(base) & 0xFF;
                            int pg = pixels.get(base + 1) & 0xFF;
                            int pb = pixels.get(base + 2) & 0xFF;
                            sum += (pr * 299 + pg * 587 + pb * 114) / 1000;
                            n++;
                        }
                    }
                    int luma = n > 0 ? (int) (sum / n) : 0;
                    line.append(ramp.charAt(Math.min(ramp.length() - 1, luma * ramp.length() / 256)));
                }
                LOGGER.info("[Iris] |{}|", line);
            }
        } catch (Throwable t) {
            LOGGER.warn("[Iris] PassTap GRID {} failed: {}", label, t.toString());
        }
    }

    /** Drops the (large) readback buffer once the tap frame is over. */
    static void release() {
        readback = null;
    }

    private static ByteBuffer buffer(int bytes) {
        if (readback == null || readback.capacity() < bytes) {
            readback = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
        readback.clear();
        return readback;
    }
}
