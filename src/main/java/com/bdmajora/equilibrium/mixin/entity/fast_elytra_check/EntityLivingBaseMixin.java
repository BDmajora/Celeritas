package com.bdmajora.equilibrium.mixin.entity.fast_elytra_check;

import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops every living entity announcing, once per tick, that it is still not elytra flying.
 *
 * <p>{@code updateElytra} ends by writing flag 7 unconditionally. Writing it means reading the flags
 * byte out of the data manager, masking a bit and writing it back — two lookups on the tracked-data
 * table for every living entity on the server, every tick, to store a value that was already there.
 *
 * <p>Reading the flag first and skipping the write when it already agrees is exactly equivalent:
 * {@code EntityDataManager.set} compares before it marks anything dirty, so a redundant write was
 * never producing a packet either. The only thing removed is the second lookup.
 *
 * <p>Redirecting this one call rather than guarding {@code setFlag} itself keeps the change local.
 * {@code setFlag} is shared with sneaking, sprinting, invisibility and glowing, and those are written
 * from paths where the extra read would not pay for itself.
 */
@Mixin(EntityLivingBase.class)
public abstract class EntityLivingBaseMixin {
    // Both are declared on Entity and inherited; Mixin resolves shadows up the hierarchy.
    @Shadow
    protected abstract boolean getFlag(int flag);

    @Shadow
    protected abstract void setFlag(int flag, boolean set);

    // The owner is deliberately omitted from the target. setFlag is declared on Entity and not
    // overridden by EntityLivingBase, so which class the call site names depends on the compiler;
    // matching on name and descriptor alone is stable either way.
    @Redirect(
            method = "updateElytra",
            at = @At(value = "INVOKE", target = "setFlag(IZ)V")
    )
    private void equilibrium$skipRedundantFlagWrite(EntityLivingBase entity, int flag, boolean value) {
        if (this.getFlag(flag) != value) {
            this.setFlag(flag, value);
        }
    }
}
