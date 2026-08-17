package com.bdmajora.impetus.iris.mixin.debug;

import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL13;
import com.bdmajora.impetus.lwjgl.GL30;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.image.BufferedImage;
import java.nio.IntBuffer;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * One-shot diagnostic for the white-screenshot bug (legacy/FullscreenTransformer packs only; modern packs such as
 * ComplementaryReimagined are unaffected).
 * <p>
 * The paradox this exists to settle: the final pass renders into vanilla's {@code framebufferMc}, vanilla's
 * {@code framebufferRender} blits that same framebuffer to the window and the window looks CORRECT, yet
 * {@code ScreenShotHelper.createScreenshot}'s {@code glGetTexImage} on {@code framebufferMc.framebufferTexture}
 * returns pure white. Both paths go through the identical cached
 * {@code GlStateManager.bindTexture(framebufferTexture)}, so exactly one of these must be true:
 * <ol>
 *   <li>the texture genuinely IS white, and the correct image reaches the window some other way; or</li>
 *   <li>the texture is fine and vanilla's cached bind is reading a DIFFERENT texture.</li>
 * </ol>
 * This probe distinguishes them directly: it re-reads the same texture through a <em>guaranteed</em> raw bind on a
 * high scratch unit (bypassing GlStateManager's cache entirely) and reports sampled pixels. If the raw read is a
 * real image, the bug is the binding (case 2). If the raw read is also white, the bug is upstream in what gets
 * written to framebufferMc (case 1) and the window is being fed from somewhere else.
 * <p>
 * Fires once per game launch; set {@code -Dimpetus.iris.screenshotProbe=false} to disable.
 */
@Mixin(ScreenShotHelper.class)
public abstract class ScreenshotProbeMixin {
    private static final Logger IMPETUS$LOGGER = LogManager.getLogger("Impetus/Iris");
    /** Scratch unit well clear of GlStateManager's 8-slot cache and of every unit the pipeline assigns. */
    private static final int IMPETUS$PROBE_UNIT = 33;
    private static boolean impetus$fired;

    @Inject(method = "createScreenshot", at = @At("HEAD"))
    private static void impetus$probeScreenshot(int width, int height, Framebuffer buffer,
                                                CallbackInfoReturnable<BufferedImage> cir) {
        if (impetus$fired || !Boolean.parseBoolean(System.getProperty("impetus.iris.screenshotProbe", "true"))) {
            return;
        }
        impetus$fired = true;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            int activeUnit = LWJGL.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
            int boundOnActive = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int fbo = LWJGL.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

            IMPETUS$LOGGER.info("[Iris] SCREENSHOT PROBE state: realActiveUnit={} boundTex2DOnActiveUnit={} "
                            + "boundFBO={} | framebufferTexture={} framebufferObject={} texSize={}x{} display={}x{} "
                            + "argWidth={} argHeight={}",
                    activeUnit, boundOnActive, fbo,
                    buffer.framebufferTexture, buffer.framebufferObject,
                    buffer.framebufferTextureWidth, buffer.framebufferTextureHeight,
                    mc.displayWidth, mc.displayHeight, width, height);

            if (boundOnActive != buffer.framebufferTexture) {
                IMPETUS$LOGGER.warn("[Iris] SCREENSHOT PROBE: the active unit does NOT currently hold "
                        + "framebufferTexture — vanilla's cached bindTexture is about to decide whether to fix that.");
            }

            // Guaranteed-correct read: raw-select a scratch unit and raw-bind the texture, so GlStateManager's
            // cache cannot suppress the bind. Restore the unit afterwards so vanilla's own read is unaffected.
            int w = buffer.framebufferTextureWidth;
            int h = buffer.framebufferTextureHeight;
            IntBuffer pixels = BufferUtils.createIntBuffer(w * h);
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + IMPETUS$PROBE_UNIT);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, buffer.framebufferTexture);
            GlStateManager.glGetTexImage(GL11.GL_TEXTURE_2D, 0, 32993 /* GL_BGRA */,
                    33639 /* GL_UNSIGNED_INT_8_8_8_8_REV */, pixels);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + activeUnit);

            IMPETUS$LOGGER.info("[Iris] SCREENSHOT PROBE rawRead(framebufferTexture) centre={} tl={} tr={} bl={} "
                            + "br={}  (0xFFFFFFFF everywhere = the texture really is white)",
                    impetus$hex(pixels.get((h / 2) * w + w / 2)),
                    impetus$hex(pixels.get(0)),
                    impetus$hex(pixels.get(w - 1)),
                    impetus$hex(pixels.get((h - 1) * w)),
                    impetus$hex(pixels.get((h - 1) * w + (w - 1))));

            // Now reproduce vanilla's exact path. createScreenshot issues the CACHED
            // GlStateManager.bindTexture(framebufferTexture) and then glGetTexImage against whatever that leaves on
            // the active unit. Everything above measured the state BEFORE that bind, which says nothing about where
            // the bind lands — reading the state at HEAD and calling it a desync is exactly the mistake that cost a
            // round. Issue the same call and read the REAL binding back.
            //
            // A white PNG is 0xFFFFFFFF, which is precisely a cleared depth texture (depth 1.0 = all bits set) read
            // as GL_BGRA/UNSIGNED_INT_8_8_8_8_REV. So the question this answers is whether the bind lands on
            // framebufferTexture or on a depth texture the pipeline left bound.
            GlStateManager.bindTexture(buffer.framebufferTexture);
            int afterUnit = LWJGL.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
            int afterBound = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            pixels.clear();
            GlStateManager.glGetTexImage(GL11.GL_TEXTURE_2D, 0, 32993, 33639, pixels);
            IMPETUS$LOGGER.info("[Iris] SCREENSHOT PROBE vanillaPath: bindTexture({}) -> realActiveUnit={} "
                            + "realBoundTex2D={} {} | readBack centre={} tl={} br={}",
                    buffer.framebufferTexture, afterUnit, afterBound,
                    afterBound == buffer.framebufferTexture
                            ? "(MATCHES: the bind is correct, so a white PNG cannot come from this read)"
                            : "(MISMATCH: the cached bind landed on the WRONG texture)",
                    impetus$hex(pixels.get((h / 2) * w + w / 2)),
                    impetus$hex(pixels.get(0)),
                    impetus$hex(pixels.get((h - 1) * w + (w - 1))));
        } catch (Throwable t) {
            IMPETUS$LOGGER.warn("[Iris] SCREENSHOT PROBE failed", t);
        }
    }

    private static String impetus$hex(int argb) {
        return String.format("0x%08X", argb);
    }
}
