package com.bdmajora.extras.mixin.profiler;

import com.bdmajora.extras.client.ProfilerHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Splits entity rendering by renderer type in the F3 profiler graph; vanilla's single "entities" slice cannot turn "something is eating my frame time" into a mod name
@Mixin(RenderManager.class)
public abstract class RenderManagerMixin {
    @Shadow
    public abstract <T extends Entity> Render<T> getEntityRenderObject(Entity entity);

    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void impetus$beginRenderEntity(Entity entity, double x, double y, double z, float yaw,
                                           float partialTicks, boolean debug, CallbackInfo ci) {
        ProfilerHelper.startSection(entity.world, this.getEntityRenderObject(entity));
    }

    @Inject(method = "renderEntity", at = @At("TAIL"))
    private void impetus$endRenderEntity(Entity entity, double x, double y, double z, float yaw,
                                         float partialTicks, boolean debug, CallbackInfo ci) {
        ProfilerHelper.endSection(entity.world, this.getEntityRenderObject(entity));
    }
}
