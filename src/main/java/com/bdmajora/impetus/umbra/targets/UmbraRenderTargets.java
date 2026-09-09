package com.bdmajora.impetus.umbra.targets;

import com.bdmajora.impetus.umbra.gl.framebuffer.UmbraFramebuffer;
import com.bdmajora.impetus.umbra.gl.texture.InternalTextureFormat;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL14;
import com.bdmajora.impetus.lwjgl.GL30;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// The pool of colour buffers (colortex0..N) and depth copies (depthtex0..2) the gbuffer, deferred and composite
// stages all draw from
// The 1.12.2 analogue of OptiFine's dfb* arrays and of modern Iris's RenderTargets, built entirely on
// UmbraRenderTarget and DepthTexture over the LWJGL abstraction — never on vanilla's Framebuffer, which supports
// only one colour attachment
// Colour targets are created LAZILY, defaulting to RGBA, so a pack that references colortex12 and nothing above it
// allocates thirteen targets rather than sixteen
// Format and size overrides must therefore be set before a target is first touched, since materialising it fixes both
public class UmbraRenderTargets {
    // colortex0..15, matching modern Iris. Everything past 7 is created only when a pack actually references it
    public static final int MAX_COLOR_BUFFERS = 16;

    private final UmbraRenderTarget[] targets = new UmbraRenderTarget[MAX_COLOR_BUFFERS];
    private final InternalTextureFormat[] formats = new InternalTextureFormat[MAX_COLOR_BUFFERS];
    // size.buffer.colortexN overrides as {width, height} per target, or null meaning "follow the render size"
    // Whether each number is a texel count or a fraction is decided by sizeRelative below, not by its magnitude
    private final float[][] sizeOverrides = new float[MAX_COLOR_BUFFERS][];
    // Per target, {xRelative, yRelative} — decided PER AXIS the way Iris does it, so a pack writing `512 0.5` gets
    // a fixed width and a half-height, which a single per-target flag could not express
    private final boolean[][] sizeRelative = new boolean[MAX_COLOR_BUFFERS][];

    private DepthTexture depthTexture;
    private DepthTexture noTranslucents; // depthtex1
    private DepthTexture noHand;         // depthtex2

    private final List<UmbraFramebuffer> ownedFramebuffers = new ArrayList<>();
    private final BufferFlipper flipper = new BufferFlipper();

    private int width;
    private int height;
    private boolean destroyed;

    public UmbraRenderTargets(int width, int height) {
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

    // Declares an explicit size for a colour buffer, from size.buffer.colortexN
    // Must be called before the buffer is first materialised, same rule as setColorFormat — once the texture exists
    // its dimensions are fixed
    // relative is per axis: true means that component is a fraction of the render size rather than a texel count
    public void setColorSize(int index, float x, float y, boolean[] relative) {
        requireValid();
        if (this.targets[index] != null) {
            throw new IllegalStateException("Color buffer " + index + " has already been created; cannot resize it");
        }
        this.sizeOverrides[index] = new float[]{x, y};
        this.sizeRelative[index] = relative.clone();
    }

    // The width this target is, or would be, created at — honouring any size.buffer override
    // Answers for a target that does not exist yet, which is what lets a pass work out its viewport before the
    // texture is materialised
    public int getWidth(int index) {
        float[] override = this.sizeOverrides[index];
        if (override == null) {
            return this.width;
        }
        // Umbra truncates the relative product ((int)(originalX * relativeX)); the clamp to 1 keeps a tiny fraction
        // from producing a zero-sized texture.
        return Math.max(1, this.sizeRelative[index][0] ? (int) (this.width * override[0]) : (int) override[0]);
    }

    public int getHeight(int index) {
        float[] override = this.sizeOverrides[index];
        if (override == null) {
            return this.height;
        }
        return Math.max(1, this.sizeRelative[index][1] ? (int) (this.height * override[1]) : (int) override[1]);
    }

    // True when this target does not match the main render size, so a pass writing it must set its own viewport —
    // leaving the main one would render into a corner of the smaller texture
    public boolean hasCustomSize(int index) {
        return this.sizeOverrides[index] != null;
    }

    // Overrides the internal format for a colour buffer, from the pack's formatN directive. Before first use, for
    // the same reason as the size
    public void setColorFormat(int index, InternalTextureFormat format) {
        requireValid();
        if (this.targets[index] != null) {
            throw new IllegalStateException("Color buffer " + index + " has already been created; cannot change its format");
        }
        this.formats[index] = format;
    }

    public UmbraRenderTarget getOrCreate(int index) {
        requireValid();
        if (this.targets[index] == null) {
            this.targets[index] = new UmbraRenderTarget(this.formats[index], getWidth(index), getHeight(index));
        }
        return this.targets[index];
    }

    public UmbraRenderTarget get(int index) {
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

    // Builds an FBO writing to the given draw buffers, attaching for each colour index whichever texture is
    // currently the BACK buffer — alt when not yet flipped, main when flipped
    // That is the whole ping-pong: the pass renders into the side it is not sampling, and the flipper then swaps
    // which is which for the next pass
    public UmbraFramebuffer createColorFramebuffer(int[] drawBuffers) {
        requireValid();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("Framebuffer must have at least one draw buffer");
        }
        UmbraFramebuffer framebuffer = new UmbraFramebuffer();
        this.ownedFramebuffers.add(framebuffer);

        // Dense attachment packing, matching Umbra's RenderTargets.createColorFramebuffer: the k-th written target is
        // attached at color-attachment POINT k (not at point = colortex index). This keeps every attachment point in
        // 0..7 regardless of the colortex indices, so packs can write colortex8..15 on hardware that exposes only 8
        // attachment points. gl_FragData[k] -> draw buffer k -> point k -> colortex[drawBuffers[k]].
        int[] densePoints = new int[drawBuffers.length];
        for (int i = 0; i < drawBuffers.length; i++) {
            densePoints[i] = i;
            UmbraRenderTarget target = getOrCreate(drawBuffers[i]);
            int texture = this.flipper.isFlipped(drawBuffers[i]) ? target.getMainTexture() : target.getAltTexture();
            framebuffer.addColorAttachment(drawBuffers[i], i, texture);
        }

        framebuffer.addDepthAttachment(this.depthTexture.getTextureId());
        framebuffer.drawBuffers(densePoints);
        framebuffer.readBuffer(0);
        checkFramebufferComplete(framebuffer, "color", drawBuffers);
        return framebuffer;
    }

    // Builds an FBO for clearing ONE side of the requested colour buffers, chosen by the alt flag
    // Attachments are packed densely exactly as the composite FBOs are, so draw buffer k clears
    // colortex[clearBuffers[k]] rather than colortex[k]
    public UmbraFramebuffer createClearFramebuffer(boolean alt, int[] clearBuffers) {
        requireValid();
        if (clearBuffers.length == 0) {
            throw new IllegalArgumentException("Framebuffer must have at least one clear buffer");
        }
        UmbraFramebuffer framebuffer = new UmbraFramebuffer();
        this.ownedFramebuffers.add(framebuffer);

        int[] densePoints = new int[clearBuffers.length];
        for (int i = 0; i < clearBuffers.length; i++) {
            densePoints[i] = i;
            UmbraRenderTarget target = getOrCreate(clearBuffers[i]);
            framebuffer.addColorAttachment(clearBuffers[i], i, alt ? target.getAltTexture() : target.getMainTexture());
        }
        framebuffer.drawBuffers(densePoints);
        checkFramebufferComplete(framebuffer, "clear", clearBuffers);
        return framebuffer;
    }

    private static void checkFramebufferComplete(UmbraFramebuffer framebuffer, String purpose, int[] drawBuffers) {
        int status = framebuffer.getStatus();
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete Umbra " + purpose + " framebuffer for draw buffers "
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
        for (int i = 0; i < this.targets.length; i++) {
            if (this.targets[i] != null) {
                // An absolutely-sized buffer keeps its size across a window resize; a relative one rescales.
                this.targets[i].resize(getWidth(i), getHeight(i));
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
        for (UmbraFramebuffer framebuffer : this.ownedFramebuffers) {
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
            throw new IllegalStateException("Tried to use destroyed UmbraRenderTargets");
        }
    }
}
