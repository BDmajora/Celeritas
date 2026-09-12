package com.bdmajora.extras.mixin.profiler;

import com.bdmajora.extras.client.ProfilerHelper;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Splits block-entity rendering by renderer type in the profiler graph; a single bad tile-entity renderer is a common cause of an unexplained frame-time cliff
@Mixin(TileEntityRendererDispatcher.class)
public class TileEntityRendererDispatcherMixin {
    @Inject(
            method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V",
            at = @At("HEAD")
    )
    private void impetus$beginRender(TileEntity tileEntity, double x, double y, double z,
                                     float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        ProfilerHelper.startSection(tileEntity.getWorld(),
                TileEntityRendererDispatcher.instance.getRenderer(tileEntity.getClass()));
    }

    @Inject(
            method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V",
            at = @At("TAIL")
    )
    private void impetus$endRender(TileEntity tileEntity, double x, double y, double z,
                                   float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        ProfilerHelper.endSection(tileEntity.getWorld(),
                TileEntityRendererDispatcher.instance.getRenderer(tileEntity.getClass()));
    }
}
