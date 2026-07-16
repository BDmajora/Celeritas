package com.bdmajora.impetus.iris.targets;

import com.bdmajora.impetus.iris.gl.framebuffer.IrisFramebuffer;
import com.bdmajora.impetus.iris.gl.texture.InternalTextureFormat;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL14;
import com.bdmajora.impetus.lwjgl.GL30;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The pool of color buffers ({@code colortex0..N}) and depth copies ({@code depthtex0..2}) used by the gbuffer,
 * deferred, and composite stages. This is the 1.12.2 analogue of OptiFine's {@code dfb*} arrays and of modern Iris's
 * {@code RenderTargets}, built entirely on {@link IrisRenderTarget}/{@link DepthTexture} over the LWJGL abstraction
 * (never vanilla's single-attachment {@code Framebuffer}).
 * <p>
 * Color targets are created lazily with a default {@link InternalTextureFormat#RGBA} unless the pack overrides the
 * format via {@link #setColorFormat(int, InternalTextureFormat)} before first use.
 */
public class IrisRenderTargets {
    /** colortex0..15, matching modern Iris. Targets past 7 are created lazily only when referenced. */
    public static final int MAX_COLOR_BUFFERS = 16;

    private final IrisRenderTarget[] targets = new IrisRenderTarget[MAX_COLOR_BUFFERS];
    private final InternalTextureFormat[] formats = new InternalTextureFormat[MAX_COLOR_BUFFERS];

    private DepthTexture depthTexture;
    private DepthTexture noTranslucents; // depthtex1
    private DepthTexture noHand;         // depthtex2

    private final List<IrisFramebuffer> ownedFramebuffers = new ArrayList<>();
    private final BufferFlipper flipper = new BufferFlipper();

    private int width;
    private int height;
    private boolean destroyed;

    public IrisRenderTargets(int width, int height) {
        this.width = width;
        this.height = height;
        for (int i = 0; i < MAX_COLOR_BUFFERS; i++) {
            this.formats[i] = InternalTextureFormat.RGBA;
        }
        this.depthTexture = createDepthTexture();
        this.noTranslucents = createDepthTexture();
        this.noHand = createDepthTexture();
    }

    private DepthTexture createDepthTexture() {
        return new DepthTexture(this.width, this.height,
                GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
    }

    /** Overrides the internal format for a color buffer. Must be called before the buffer is first materialised. */
    public void setColorFormat(int index, InternalTextureFormat format) {
        requireValid();
        if (this.targets[index] != null) {
            throw new IllegalStateException("Color buffer " + index + " has already been created; cannot change its format");
        }
        this.formats[index] = format;
    }

    public IrisRenderTarget getOrCreate(int index) {
        requireValid();
        if (this.targets[index] == null) {
            this.targets[index] = new IrisRenderTarget(this.formats[index], this.width, this.height);
        }
        return this.targets[index];
    }

    public IrisRenderTarget get(int index) {
        return this.targets[index];
    }

    public BufferFlipper getBufferFlipper() {
        return this.flipper;
    }

    public DepthTexture getDepthTexture() {
        return this.depthTexture;
    }

    public DepthTexture getDepthTextureNoTranslucents() {
        return this.noTranslucents;
    }

    public DepthTexture getDepthTextureNoHand() {
        return this.noHand;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    /**
     * Builds an FBO that writes to {@code drawBuffers}, attaching for each color index the texture that is currently
     * the back buffer (alt if not yet flipped, main if flipped), so a pass writes to the side it isn't reading.
     */
    public IrisFramebuffer createColorFramebuffer(int[] drawBuffers) {
        requireValid();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("Framebuffer must have at least one draw buffer");
        }
        IrisFramebuffer framebuffer = new IrisFramebuffer();
        this.ownedFramebuffers.add(framebuffer);

        // Dense attachment packing, matching Iris's RenderTargets.createColorFramebuffer: the k-th written target is
        // attached at color-attachment POINT k (not at point = colortex index). This keeps every attachment point in
        // 0..7 regardless of the colortex indices, so packs can write colortex8..15 on hardware that exposes only 8
        // attachment points. gl_FragData[k] -> draw buffer k -> point k -> colortex[drawBuffers[k]].
        int[] densePoints = new int[drawBuffers.length];
        for (int i = 0; i < drawBuffers.length; i++) {
            densePoints[i] = i;
            IrisRenderTarget target = getOrCreate(drawBuffers[i]);
            int texture = this.flipper.isFlipped(drawBuffers[i]) ? target.getMainTexture() : target.getAltTexture();
            framebuffer.addColorAttachment(drawBuffers[i], i, texture);
        }

        framebuffer.addDepthAttachment(this.depthTexture.getTextureId());
        framebuffer.drawBuffers(densePoints);
        framebuffer.readBuffer(0);
        checkFramebufferComplete(framebuffer, "color", drawBuffers);
        return framebuffer;
    }

    /**
     * Builds an FBO for clearing one side of the requested color buffers. Attachments are packed densely just like
     * composite FBOs: draw buffer k clears colortex[clearBuffers[k]].
     */
    public IrisFramebuffer createClearFramebuffer(boolean alt, int[] clearBuffers) {
        requireValid();
        if (clearBuffers.length == 0) {
            throw new IllegalArgumentException("Framebuffer must have at least one clear buffer");
        }
        IrisFramebuffer framebuffer = new IrisFramebuffer();
        this.ownedFramebuffers.add(framebuffer);

        int[] densePoints = new int[clearBuffers.length];
        for (int i = 0; i < clearBuffers.length; i++) {
            densePoints[i] = i;
            IrisRenderTarget target = getOrCreate(clearBuffers[i]);
            framebuffer.addColorAttachment(clearBuffers[i], i, alt ? target.getAltTexture() : target.getMainTexture());
        }
        framebuffer.drawBuffers(densePoints);
        checkFramebufferComplete(framebuffer, "clear", clearBuffers);
        return framebuffer;
    }

    private static void checkFramebufferComplete(IrisFramebuffer framebuffer, String purpose, int[] drawBuffers) {
        int status = framebuffer.getStatus();
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete Iris " + purpose + " framebuffer for draw buffers "
                    + Arrays.toString(drawBuffers) + ": status=" + status);
        }
    }

    public void resize(int newWidth, int newHeight) {
        requireValid();
        if (newWidth == this.width && newHeight == this.height) {
            return;
        }
        this.width = newWidth;
        this.height = newHeight;
        for (IrisRenderTarget target : this.targets) {
            if (target != null) {
                target.resize(newWidth, newHeight);
            }
        }
        this.depthTexture.resize(newWidth, newHeight);
        this.noTranslucents.resize(newWidth, newHeight);
        this.noHand.resize(newWidth, newHeight);
    }

    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        for (IrisFramebuffer framebuffer : this.ownedFramebuffers) {
            framebuffer.destroy();
        }
        this.ownedFramebuffers.clear();
        for (int i = 0; i < this.targets.length; i++) {
            if (this.targets[i] != null) {
                this.targets[i].destroy();
                this.targets[i] = null;
            }
        }
        this.depthTexture.destroy();
        this.noTranslucents.destroy();
        this.noHand.destroy();
    }

    private void requireValid() {
        if (this.destroyed) {
            throw new IllegalStateException("Tried to use destroyed IrisRenderTargets");
        }
    }
}
