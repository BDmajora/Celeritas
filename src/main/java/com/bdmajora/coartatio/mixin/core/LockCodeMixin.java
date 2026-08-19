package com.bdmajora.coartatio.mixin.core;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.LockCode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reuses {@code LockCode.EMPTY_CODE} instead of allocating an empty lock per tile entity.
 *
 * <p>From LoliASM. Vanilla defines {@code EMPTY_CODE} for exactly this purpose and then forgets to
 * use it on one path: {@code fromNBT} returns {@code EMPTY_CODE} when the {@code Lock} tag is
 * <i>absent</i>, but allocates {@code new LockCode("")} when the tag is present and empty — which is
 * what every lockable tile entity that has ever been saved actually writes.
 *
 * <p>So every chest, furnace, hopper, dispenser, dropper, brewing stand and beacon in a world gets
 * its own empty {@code LockCode} plus its own empty {@code String} on load. Individually trivial;
 * multiplied by the tile entity count of a large world, not.
 *
 * <p>This is a vanilla oversight rather than a design tradeoff — the constant exists, and the two
 * paths are meant to agree.
 */
@Mixin(LockCode.class)
public abstract class LockCodeMixin {
    @Shadow
    @Final
    public static LockCode EMPTY_CODE;

    @Inject(method = "fromNBT", at = @At("HEAD"), cancellable = true)
    private static void coartatio$shareEmptyLock(NBTTagCompound nbt, CallbackInfoReturnable<LockCode> cir) {
        if (nbt.hasKey("Lock", 8) && nbt.getString("Lock").isEmpty()) {
            cir.setReturnValue(EMPTY_CODE);
        }
    }
}
