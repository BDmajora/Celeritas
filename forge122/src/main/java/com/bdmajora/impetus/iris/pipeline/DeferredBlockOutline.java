package com.bdmajora.impetus.iris.pipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.RayTraceResult;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Replays the block selection box <em>after</em> the composite/final chain, for packs that ship no
 * {@code gbuffers_line}.
 * <p>
 * <b>Why this is a deliberate 1.12.2 deviation from Iris and OptiFine.</b> Both of those draw the outline inside the
 * world pass, into the gbuffer. That works on modern packs because they ship a {@code gbuffers_line} written for it.
 * A pre-deferred pack has none, so OptiFine's fallback chain drops the box into {@code gbuffers_basic} — and
 * <b>colortex0 is albedo, not the finished image</b>. Whatever lands there (the pack's program, or vanilla
 * fixed-function) is multiplied by the scene lighting in the composite chain afterwards. Next to a torch that light
 * is warm, so vanilla's 40% black line comes back out as a dark red line that shifts with view angle, because the
 * lighting does.
 * <p>
 * That is not fixable by changing <em>what</em> is written pre-composite — the colour, the blend mode, the draw-buffer
 * mask and the bound program were each tried and none of them moved it, because every one of them still wrote into
 * albedo. The only thing that reproduces vanilla's appearance is darkening the <em>finished</em> image, which means
 * drawing after {@link IrisRenderingPipeline#finishWorldRendering()}.
 * <p>
 * The composite passes clobber the projection and modelview matrices (the post-composite hand path rebuilds its own
 * from scratch), so the world matrices are captured at the original draw site and restored here rather than assumed
 * to survive.
 */
public final class DeferredBlockOutline {
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);

    /**
     * <b>ON BY DEFAULT — this is temporary diagnostic state, remove it once the artifact is identified.</b> Draws the
     * replayed box in opaque green and logs once that the replay ran. It exists because "is this artifact the
     * selection outline or not?" cannot be settled from a screenshot when the outline is a thin dark line on dark
     * geometry, and guessing at that question has already cost several wrong fixes. Turn off with
     * {@code -Dimpetus.iris.outlineDebug=false}.
     */
    private static final boolean DEBUG =
            !"false".equalsIgnoreCase(System.getProperty("impetus.iris.outlineDebug", "true"));
    private static boolean loggedDraw;

    private static boolean pending;
    /** Guards the replay's own call to {@code drawSelectionBox} so the capture hook lets it through. */
    private static boolean replaying;

    private static EntityPlayer player;
    private static RayTraceResult target;
    private static float partialTicks;

    private DeferredBlockOutline() {
    }

    /** {@return true while the replay below is driving vanilla's own {@code drawSelectionBox}} */
    public static boolean isReplaying() {
        return replaying;
    }

    /**
     * Records the pending outline and the exact world matrices it would have been drawn with. Called from the
     * cancelled {@code drawSelectionBox}, where the matrices are still the world camera's.
     */
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

    /** Drops a captured outline without drawing it (the frame ended early, or the pipeline went away). */
    public static void discard() {
        pending = false;
        player = null;
        target = null;
    }

    /**
     * Draws the captured outline into the finished image. Must run after the final pass, while Minecraft's own
     * framebuffer (and its world depth) is bound.
     */
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

        if (DEBUG && !loggedDraw) {
            loggedDraw = true;
            org.apache.logging.log4j.LogManager.getLogger("Impetus/Iris").info(
                    "[Iris] Deferred block outline REPLAY is running (target {}). With -Dimpetus.iris.outlineDebug"
                            + " the box is drawn bright green: if you see a green box, the selection outline is this"
                            + " draw and nothing else; if the artifact is still there in its original colour next to"
                            + " a green box, the artifact is NOT the selection outline.",
                    capturedTarget.getBlockPos());
        }

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
            if (DEBUG) {
                // Vanilla passes its colour per vertex, so a constant only wins if nothing else is driving it — which
                // is exactly what we want to observe here. Restored by vanilla's own postDraw resetColor().
                GlStateManager.color(0.0F, 1.0F, 0.0F, 1.0F);
            }
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
