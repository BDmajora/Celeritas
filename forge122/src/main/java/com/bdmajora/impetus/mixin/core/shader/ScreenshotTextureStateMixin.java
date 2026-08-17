package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.iris.gl.GlTextureUnits;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.image.BufferedImage;

/**
 * Keeps a screenshot from poisoning the texture state the window blit depends on.
 * <p>
 * Vanilla's {@code createScreenshot} ends with {@code GlStateManager.bindTexture(framebufferIn.framebufferTexture)}
 * and never undoes it. That is safe in vanilla and OptiFine, and it is NOT safe here, for two reasons that both
 * outlive the screenshot — which is what makes the symptom "white until the game is restarted" rather than a flash.
 * <p>
 * <b>1. It can write the binding into the wrong cache slot.</b> {@code GlStateManager.bindTexture} binds to whatever
 * texture unit real GL currently has selected, but records that binding against the unit its own
 * {@code activeTextureUnit} cache believes is selected. The pipeline drives high scratch units with raw
 * {@code LWJGL.glActiveTexture}, so those two can disagree at F2 time. When they do, the screenshot files
 * {@code framebufferTexture} under a unit that does not hold it — and from then on
 * {@code Framebuffer.bindFramebufferTexture()}, which is the fullscreen blit that puts the world on screen, issues the
 * identical cached {@code bindTexture(framebufferTexture)} and gets a <em>no-op</em> every frame. The blit then samples
 * whatever genuinely occupies that unit. A depth or shadow texture read as colour is 1.0 across the board: a white
 * screen, every frame, until a restart resyncs the cache. Nothing in the frame loop repairs it, because from
 * GlStateManager's point of view the correct texture is already bound.
 * <p>
 * {@link GlTextureUnits#resetToUnit0()} at HEAD removes the precondition: it lands the cache and real GL on unit 0
 * together (stepping through unit 1 so the second call cannot itself be swallowed by the cache), so vanilla's bind is
 * recorded against the unit it actually targets.
 * <p>
 * <b>2. It leaves the render target bound as a sampler.</b> After the screenshot, {@code framebufferMc}'s colour
 * texture sits on unit 0 while the shader pipeline goes on to render <em>into</em> that same framebuffer. Sampling a
 * texture that is simultaneously attached to the bound framebuffer is undefined in GL. Unbinding at RETURN costs
 * nothing — vanilla is finished with the texture by then, and unit 0 is rebound before anything draws.
 * <p>
 * Deliberately a real fix rather than a devtool: it is unconditional, cheap (two selector calls and one bind, once per
 * screenshot), and correct even if the desync never happens to occur.
 */
@Mixin(ScreenShotHelper.class)
public class ScreenshotTextureStateMixin {
    @Inject(method = "createScreenshot", at = @At("HEAD"))
    private static void impetus$syncTextureUnitBeforeCapture(int width, int height, Framebuffer buffer,
                                                             CallbackInfoReturnable<BufferedImage> cir) {
        GlTextureUnits.resetToUnit0();
    }

    @Inject(method = "createScreenshot", at = @At("RETURN"))
    private static void impetus$releaseFramebufferTexture(int width, int height, Framebuffer buffer,
                                                          CallbackInfoReturnable<BufferedImage> cir) {
        GlStateManager.bindTexture(0);
    }
}
