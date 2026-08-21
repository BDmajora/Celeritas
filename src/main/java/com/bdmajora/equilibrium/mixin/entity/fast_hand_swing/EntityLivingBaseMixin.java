package com.bdmajora.equilibrium.mixin.entity.fast_hand_swing;

import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the swing animation update for entities that are not swinging.
 *
 * <p>Vanilla runs this for every living entity every tick, and the last thing it does —
 * {@code swingProgress = swingProgressInt / getArmSwingAnimationEnd()} — is a division whose divisor
 * costs two potion effect lookups to obtain. When the entity is not swinging, the numerator is zero
 * and so is the result, so all of that is spent producing a value that was already correct.
 *
 * <p>The guard also requires {@code swingProgress} to already be zero, so the tick on which a swing
 * finishes still runs and writes it. Without that an entity would freeze mid-animation the moment its
 * swing was cancelled rather than played out.
 */
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
