package com.bdmajora.equilibrium.mixin.entity.fast_hand_swing;

import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Skips updateArmSwingProgress for idle entities; vanilla always pays two potion-effect lookups for a division that just produces zero again
// Guard also requires swingProgress == 0 already, so the tick a swing actually finishes still runs and writes the final value
@Mixin(EntityLivingBase.class)
public abstract class EntityLivingBaseMixin {
    @Shadow
    public boolean isSwingInProgress;

    @Shadow
    public int swingProgressInt;

    @Shadow
    public float swingProgress;

    @Inject(method = "updateArmSwingProgress", at = @At("HEAD"), cancellable = true)
    private void equilibrium$skipIdleSwing(CallbackInfo ci) {
        if (!this.isSwingInProgress && this.swingProgressInt == 0 && this.swingProgress == 0.0F) {
            ci.cancel();
        }
    }
}
