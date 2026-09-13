package com.bdmajora.extras.mixin.render.entity;

import com.bdmajora.extras.client.budget.RenderBudgetController;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// RenderManager draws the shadow and fire after doRender regardless of what doRender did, so a budget-skipped mob would otherwise leave a shadow hovering over empty ground
@Mixin(Render.class)
public abstract class RenderShadowMixin<T extends Entity> {
    @Inject(
            method = "doRenderShadowAndFire(Lnet/minecraft/entity/Entity;DDDFF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void impetus$skipShadowOfCulledEntity(Entity entity, double x, double y, double z,
                                                  float yaw, float partialTicks, CallbackInfo ci) {
        if (RenderBudgetController.wasCulledThisFrame(entity)) {
            ci.cancel();
        }
    }
}
