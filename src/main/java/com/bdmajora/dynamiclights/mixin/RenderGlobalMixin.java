package com.bdmajora.dynamiclights.mixin;

import com.bdmajora.dynamiclights.DynamicLights;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the per-frame light source update.
 *
 * <p>Hung off {@code renderEntities} rather than a client tick because the update needs a
 * {@link RenderGlobal} to schedule chunk rebuilds against, and because a source that moves between
 * ticks should re-light on the frame it moves.
 *
 * <p>Injects at HEAD, so it does not collide with the two {@code renderEntities} injectors Impetus'
 * own {@code core/terrain/RenderGlobalMixin} already carries further into the method.
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalMixin {
    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void impetus$updateDynamicLights(Entity renderViewEntity, ICamera camera, float partialTicks,
                                             CallbackInfo ci) {
        DynamicLights.engine().updateAll((RenderGlobal) (Object) this);
    }
}
