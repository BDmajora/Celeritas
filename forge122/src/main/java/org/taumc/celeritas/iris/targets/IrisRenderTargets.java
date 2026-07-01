package org.taumc.celeritas.iris.targets;

import org.taumc.celeritas.iris.gl.framebuffer.IrisFramebuffer;
import org.taumc.celeritas.iris.gl.texture.InternalTextureFormat;
import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL14;

import java.util.ArrayList;
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
    /** OptiFine 1.12.2 exposes colortex0..7; Iris later widened this to 16. Keep the classic 8 for parity. */
    public static final int MAX_COLOR_BUFFERS = 8;

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

        for (int drawBuffer : drawBuffers) {
            IrisRenderTarget target = getOrCreate(drawBuffer);
            int texture = this.flipper.isFlipped(drawBuffer) ? target.getMainTexture() : target.getAltTexture();
            framebuffer.addColorAttachment(drawBuffer, texture);
        }

        framebuffer.addDepthAttachment(this.depthTexture.getTextureId());
        framebuffer.drawBuffers(drawBuffers);
        return framebuffer;
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
