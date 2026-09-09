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

// Ticks the light of item frames and paintings
// Needed because EntityHanging#onUpdate does not call up to Entity#onUpdate, so the shared onEntityUpdate hook
// never fires for these — a torch sitting in an item frame would stay dark
// Only the TICK is added here; the luminance still comes from the base implementation, which routes through the
// EntityItemFrame handler
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
