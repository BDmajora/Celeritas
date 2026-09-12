package com.bdmajora.impetus.umbra.pipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.RayTraceResult;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Replays the block selection box after the composite chain, for packs with no gbuffers_line
// Drawn in the world pass it lands in colortex0, which is albedo, and the composite lighting turns vanilla's
// black line dark red beside a torch. Only darkening the finished image reproduces vanilla, so the world matrices
// are captured at the original draw and restored here
public final class DeferredBlockOutline {
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);

    private static boolean pending;
    // Set while the replay below is calling vanilla's own drawSelectionBox, so the capture hook lets that one
    // through instead of cancelling and re-capturing it into an infinite loop
    private static boolean replaying;

    private static EntityPlayer player;
    private static RayTraceResult target;
    private static float partialTicks;

    private DeferredBlockOutline() {
    }

    // Read by the capture hook to tell our own replay apart from vanilla's original call
    public static boolean isReplaying() {
        return replaying;
    }

    // Records the pending outline and the exact matrices it would have been drawn with
    // Called from the CANCELLED drawSelectionBox, which is the only point where the projection and modelview are
    // still the world camera's — by the time the replay runs the composite chain has overwritten both
    public static void capture(EntityPlayer capturedPlayer, RayTraceResult capturedTarget, float capturedPartialTicks) {
        PROJECTION.clear();
        MODELVIEW.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        PROJECTION.rewind();
        MODELVIEW.rewind();

        player = capturedPlayer;
        target = capturedTarget;
        partialTicks = capturedPartialTicks;
        pending = true;
    }

    // Drops a captured outline without drawing it, for when the frame ended early or the pipeline went away
    // Without this a stale capture would be replayed into the next frame, drawing a box around a block the player
    // is no longer looking at
    public static void discard() {
        pending = false;
        player = null;
        target = null;
    }

    // Draws the captured outline into the finished image
    // Must run after the final pass, with Minecraft's own framebuffer bound — and its world depth, since the box
    // is depth-tested so its far edges stay hidden behind the block exactly as vanilla draws them
    public static void drawIfPending() {
        if (!pending) {
            return;
        }
        EntityPlayer capturedPlayer = player;
        RayTraceResult capturedTarget = target;
        discard();
        if (capturedPlayer == null || capturedTarget == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.renderGlobal == null) {
            return;
        }

        // The final pass leaves ITS framebuffer bound, not the one that gets presented. Measured: without this the
        // replay runs (the log line below fires) and lands zero pixels on screen. Bind Minecraft's own framebuffer so
        // the box darkens the image the player actually sees.
        if (mc.getFramebuffer() != null) {
            mc.getFramebuffer().bindFramebuffer(false);
        }

        // The final pass leaves its own program bound; the outline is fixed-function geometry.
        LWJGL.glUseProgram(0);

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.multMatrix(PROJECTION);
        PROJECTION.rewind();

        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.multMatrix(MODELVIEW);
        MODELVIEW.rewind();

        // finishWorldRendering leaves depth off for the fullscreen passes; the box is depth-tested against the world
        // so its far edges stay hidden behind the block, exactly as vanilla draws it. It writes no depth of its own
        // (drawSelectionBox sets depthMask(false) itself).
        GlStateManager.enableDepth();

        replaying = true;
        try {
            mc.renderGlobal.drawSelectionBox(capturedPlayer, capturedTarget, 0, partialTicks);
        } finally {
            replaying = false;

            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();

            // Hand the composite chain's state back exactly as finishWorldRendering left it.
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableBlend();
        }
    }
}
