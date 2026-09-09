package com.bdmajora.impetus.umbra.pipeline;

import com.bdmajora.impetus.umbra.gl.framebuffer.UmbraFramebuffer;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Produces OptiFine's centerDepthSmooth uniform: the depth value at the centre of the screen, smoothed over time
// The half-life comes from the pack as `const float centerDepthHalflife` in seconds, defaulting to 1.0
// Depth-of-field focus and auto-exposure both key off this — it is how a pack knows how far away the thing the
// player is looking at is
// The sample is a synchronous one-pixel glReadPixels from a dedicated depth-only read framebuffer wrapping
// depthtex0, the same approach the original 1.12-era shaders mod took. One pixel per frame is cheap even with the
// implied sync; if it ever shows up in a profile it can move to a PBO ping-pong without touching any caller
// The result is published through a static so CommonUniforms can register the uniform without threading the
// pipeline instance through every program-compile path, consistent with the port's other uniforms
public final class CenterDepthSampler {
    // Latest smoothed value, read by uniform suppliers on any program
    // Starts at 1.0, the far-plane depth, so the first frame reads "looking at nothing" rather than "focused on
    // the near plane" and the DoF does not blur the whole world for a frame
    private static float currentSmoothed = 1.0f;

    private final ByteBuffer pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
    private UmbraFramebuffer readFramebuffer;
    private int attachedDepthTexture = -1;
    private float smoothed = 1.0f;
    private boolean initialized;

    public static float getCenterDepthSmooth() {
        return currentSmoothed;
    }

    // Samples and smooths the centre depth
    // depthTexture is depthtex0's GL id, passed in per call rather than cached because it changes across resizes
    // A halfLife of 0 or less disables smoothing and takes the raw sample
    // Leaves the READ framebuffer binding pointing at the internal depth-only FBO; callers bind whatever they need
    // afterwards, so this deliberately does not restore it
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
