package com.bdmajora.dynamiclights.mixin.lightsource;

import com.bdmajora.dynamiclights.client.DynamicLightSource;
import com.bdmajora.dynamiclights.client.DynamicLightsEngine;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ticks the light of item frames and paintings.
 *
 * <p>{@code EntityHanging#onUpdate} does not call up to {@code Entity#onUpdate}, so the shared
 * {@code onEntityUpdate} hook never fires for these — a torch in an item frame would stay dark
 * without this. Luminance itself still comes from the base implementation, which routes through the
 * {@code EntityItemFrame} handler.
 */
@Mixin(EntityHanging.class)
public abstract class EntityHangingMixin extends Entity implements DynamicLightSource {
    private EntityHangingMixin(World world) {
        super(world);
    }

    @Inject(method = "onUpdate", at = @At("TAIL"))
    private void impetus$onHangingTick(CallbackInfo ci) {
        if (!this.world.isRemote) {
            return;
        }

        if (this.isDead) {
            this.impetus$setDynamicLightEnabled(false);
            return;
        }

        this.impetus$dynamicLightTick();
        DynamicLightsEngine.updateTracking(this);
    }
}
