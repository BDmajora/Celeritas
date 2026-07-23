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

    /**
     * Unclamped HDR readback: reads the given color attachment as float and logs the max channel value plus separate
     * means for the top screen band (sky/horizon) and bottom band (foreground). The byte-based {@link #logColor}
     * clamps everything to 1.0, hiding exactly the over-1 values that blow the horizon out; this exposes them so we
     * can see whether the fog source (colortex7) or the composited scene (colortex1) is where values exceed 1.
     */
    static void logColorHDR(String label, int attachment, int width, int height) {
        try {
            if (attachment >= 0) {
                LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0 + attachment);
            }
            ByteBuffer pixels = buffer(width * height * 4 * 4);
            LWJGL.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
            if (attachment >= 0) {
                LWJGL.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            }
            FloatBuffer f = pixels.asFloatBuffer();
            float max = 0.0f;
            int over1 = 0;
            int count = width * height;
            // Track the single brightest pixel so we can pinpoint what the blown values ARE: location (as a fraction
            // of the screen, 0,0 = bottom-left) + its raw RGB. e.g. a hot pixel at (0.5,0.55) with RGB near the sun's
            // color = sun; a band at y~0.5 over water = specular; distributed = terrain lighting.
            float argMaxLuma = -1.0f;
            int argX = -1;
            int argY = -1;
            float argR = 0, argG = 0, argB = 0;
            // 8 horizontal bands from screen TOP (band 0) to BOTTOM (band 7). glReadPixels y=0 is the bottom row, so
            // band index = 7 - (y * 8 / height). Report each band's mean luma, over-1 fraction, and max — this pins
            // WHERE the blown (>1, up to the 50 clamp) pixels live: a small hot spot high up = the sun; a mid band =
            // the horizon; spread across the bottom = terrain lighting.
            int bands = 8;
            double[] bandSum = new double[bands];
            long[] bandCount = new long[bands];
            int[] bandOver1 = new int[bands];
            float[] bandMax = new float[bands];
            for (int y = 0; y < height; y++) {
                int band = bands - 1 - Math.min(bands - 1, y * bands / height);
                for (int x = 0; x < width; x++) {
                    int base = (y * width + x) * 4;
                    float pr = f.get(base);
                    float pg = f.get(base + 1);
                    float pb = f.get(base + 2);
                    float luma = 0.2126f * pr + 0.7152f * pg + 0.0722f * pb;
                    float pmax = Math.max(pr, Math.max(pg, pb));
                    max = Math.max(max, pmax);
                    if (luma > argMaxLuma) {
                        argMaxLuma = luma;
                        argX = x;
                        argY = y;
                        argR = pr;
                        argG = pg;
                        argB = pb;
                    }
                    boolean hot = pr > 1.0f || pg > 1.0f || pb > 1.0f;
                    if (hot) {
                        over1++;
                        bandOver1[band]++;
                    }
                    bandSum[band] += luma;
                    bandCount[band]++;
                    bandMax[band] = Math.max(bandMax[band], pmax);
                }
            }
            StringBuilder bandStr = new StringBuilder();
            for (int b = 0; b < bands; b++) {
                if (b > 0) {
                    bandStr.append(" | ");
                }
                bandStr.append(String.format(Locale.ROOT, "b%d luma=%.2f over1=%.0f%% max=%.1f",
                        b, bandCount[b] == 0 ? 0.0 : bandSum[b] / bandCount[b],
                        bandCount[b] == 0 ? 0.0 : bandOver1[b] * 100.0 / bandCount[b], bandMax[b]));
            }
            LOGGER.info(String.format(Locale.ROOT,
                    "[Iris] PassTap HDR %s: maxChannel=%.3f over1=%.1f%% brightestAt=(%.2f,%.2f) rgb=(%.1f,%.1f,%.1f)  [top->bottom] %s",
                    label, max, over1 * 100.0 / count,
                    width == 0 ? 0.0 : argX / (double) width, height == 0 ? 0.0 : argY / (double) height,
                    argR, argG, argB, bandStr));
        } catch (Throwable t) {
            LOGGER.warn("[Iris] PassTap HDR {} failed: {}", label, t.toString());
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
            // Per-band sky fraction (atOne%), top->bottom, so we can tell whether the horizon 50-band (band b4) sits on
            // SKY (depth==1 → a sky program overflowed) or on GEOMETRY (depth<1 → terrain/water fog/lighting).
            int bands = 8;
            long[] bandCount = new long[bands];
            int[] bandAtOne = new int[bands];
            for (int y = 0; y < height; y++) {
                int band = bands - 1 - Math.min(bands - 1, y * bands / height);
                for (int x = 0; x < width; x++) {
                    float d = depths.get(y * width + x);
                    sum += d;
                    min = Math.min(min, d);
                    bandCount[band]++;
                    if (d >= 1.0f) {
                        atOne++;
                        bandAtOne[band]++;
                    }
                }
            }
            StringBuilder bandStr = new StringBuilder();
            for (int b = 0; b < bands; b++) {
                if (b > 0) {
                    bandStr.append(" | ");
                }
                bandStr.append(String.format(Locale.ROOT, "b%d sky=%.0f%%",
                        b, bandCount[b] == 0 ? 0.0 : bandAtOne[b] * 100.0 / bandCount[b]));
            }
            LOGGER.info(String.format(Locale.ROOT,
                    "[Iris] PassTap %s: depth mean=%.6f min=%.6f atOne=%.1f%%  [top->bottom] %s",
                    label, sum / count, min, atOne * 100.0 / count, bandStr));
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
