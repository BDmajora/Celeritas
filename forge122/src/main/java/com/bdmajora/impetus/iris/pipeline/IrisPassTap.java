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
            ByteBuffer pixels = buffer(width * height * 4);
            LWJGL.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            if (attachment >= 0) {
                LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            }
            long r = 0;
            long g = 0;
            long b = 0;
            long a = 0;
            int black = 0;
            int max = 0;
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
            }
            LOGGER.info(String.format(Locale.ROOT,
                    "[Iris] PassTap %s: mean=(%.2f, %.2f, %.2f, %.2f) black=%.1f%% maxChannel=%d",
                    label, r / (double) count, g / (double) count, b / (double) count, a / (double) count,
                    black * 100.0 / count, max));
        } catch (Throwable t) {
            LOGGER.warn("[Iris] PassTap {} failed: {}", label, t.toString());
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
