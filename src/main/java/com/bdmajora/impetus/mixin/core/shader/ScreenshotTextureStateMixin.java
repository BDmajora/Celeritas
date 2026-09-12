package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.umbra.gl.GlTextureUnits;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.image.BufferedImage;

// Keeps a screenshot from poisoning texture state: vanilla ends createScreenshot with bindTexture(framebufferTexture), which GlStateManager may record against the wrong cached unit (the pipeline drives units via raw glActiveTexture), turning every later blit into a no-op and the screen white until restart; resetToUnit0() at HEAD and an unbind at RETURN (the render target must not stay bound as a sampler) fix both
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
