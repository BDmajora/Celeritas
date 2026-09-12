package com.bdmajora.impetus.mixin.features.render.tileentity.piston;

import net.minecraft.client.renderer.tileentity.TileEntityPistonRenderer;
import net.minecraft.tileentity.TileEntityPiston;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(TileEntityPistonRenderer.class)
public class TileEntityPistonRendererMixin {
    // pistons flicker if the TESR stops rendering before the chunk mesh updates with the block model,
    // so the progress the redirect reports never reaches the value that would end the TESR draw
    // @author / @reason are Mixin's required metadata on an overwrite-class injector
    @Redirect(method = "render(Lnet/minecraft/tileentity/TileEntityPiston;DDDFIF)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntityPiston;getProgress(F)F", ordinal = 0))
    private float alwaysRenderTESR(TileEntityPiston instance, float ticks) {
        return 0f;
    }
}
