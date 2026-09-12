package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.umbra.gl.framebuffer.UmbraFramebuffer;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// OptiFine's centerDepthSmooth, the depth at screen centre smoothed by the pack's centerDepthHalflife; one synchronous glReadPixels per frame from a depth-only FBO over depthtex0
public final class CenterDepthSampler {
    // Latest smoothed value, read by uniform suppliers; starts at 1.0 (far plane) so the first frame reads "looking at nothing" and DoF does not blur the whole world for a frame
    private static float currentSmoothed = 1.0f;

    private final ByteBuffer pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
    private UmbraFramebuffer readFramebuffer;
    private int attachedDepthTexture = -1;
    private float smoothed = 1.0f;
    private boolean initialized;

    // The smoothed centre depth for the centerDepthSmooth uniform
    public static float getCenterDepthSmooth() {
        return currentSmoothed;
    }

    // Samples and smooths the centre depth; depthTexture is passed per call since it changes on resize, halfLife <= 0 takes the raw sample, and the READ framebuffer is deliberately left pointing at the internal FBO
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

    // Frees the readback resources
    public void destroy() {
        if (this.readFramebuffer != null) {
            this.readFramebuffer.destroy();
            this.readFramebuffer = null;
        }
        this.attachedDepthTexture = -1;
        currentSmoothed = 1.0f;
    }
}
