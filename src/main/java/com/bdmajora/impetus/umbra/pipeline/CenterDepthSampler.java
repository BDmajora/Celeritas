package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.umbra.gl.framebuffer.UmbraFramebuffer;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Produces the OptiFine {@code centerDepthSmooth} uniform: the depth-buffer value at the centre of the screen,
 * smoothed over time with a configurable half-life ({@code const float centerDepthHalflife} in the pack, seconds,
 * default 1.0). Depth-of-field and auto-exposure effects key off this value.
 *
 * <p>The sample is a synchronous 1-pixel {@code glReadPixels} from a dedicated depth-only read framebuffer wrapping
 * {@code depthtex0} — the same strategy the original 1.12-era shaders mod used. One pixel per frame is cheap even
 * with the implied sync; if it ever shows up in profiles, this can move to a PBO ping-pong without changing callers.
 *
 * <p>The smoothed value is published through a static so {@code CommonUniforms} can register the uniform without
 * threading the pipeline instance through every program-compile path (consistent with this port's other uniforms).
 */
public final class CenterDepthSampler {
    /** Latest smoothed centre depth, readable by uniform suppliers on any program. */
    private static float currentSmoothed = 1.0f;

    private final ByteBuffer pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
    private UmbraFramebuffer readFramebuffer;
    private int attachedDepthTexture = -1;
    private float smoothed = 1.0f;
    private boolean initialized;

    public static float getCenterDepthSmooth() {
        return currentSmoothed;
    }

    /**
     * Samples and smooths the centre depth. Callers may bind any framebuffer afterwards; this leaves the READ
     * framebuffer binding pointing at the internal depth-only FBO.
     *
     * @param depthTexture the GL id of {@code depthtex0} (may change across resizes)
     * @param halfLife     smoothing half-life in seconds; {@code <= 0} disables smoothing
     */
    public void sample(int depthTexture, int width, int height, float frameTime, float halfLife) {
        if (width <= 0 || height <= 0) {
            return;
        }

        if (this.readFramebuffer == null || this.attachedDepthTexture != depthTexture) {
            if (this.readFramebuffer != null) {
                this.readFramebuffer.destroy();
            }
            this.readFramebuffer = new UmbraFramebuffer();
            this.readFramebuffer.bind();
            this.readFramebuffer.addDepthAttachment(depthTexture);
            this.readFramebuffer.noDrawBuffers();
            this.attachedDepthTexture = depthTexture;
        }

        this.readFramebuffer.bindAsReadBuffer();

        this.pixel.clear();
        LWJGL.glReadPixels(width / 2, height / 2, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, this.pixel);
        float sampled = this.pixel.asFloatBuffer().get(0);

        if (!this.initialized) {
            this.initialized = true;
            this.smoothed = sampled;
        } else if (halfLife <= 0.0f) {
            this.smoothed = sampled;
        } else {
            // Exponential approach with the given half-life: after `halfLife` seconds, half the gap is closed.
            float rate = 1.0f - (float) Math.pow(0.5, Math.max(frameTime, 1.0e-4f) / halfLife);
            this.smoothed += (sampled - this.smoothed) * rate;
        }

        currentSmoothed = this.smoothed;

        LWJGL.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
    }

    public void destroy() {
        if (this.readFramebuffer != null) {
            this.readFramebuffer.destroy();
            this.readFramebuffer = null;
        }
        this.attachedDepthTexture = -1;
        currentSmoothed = 1.0f;
    }
}
