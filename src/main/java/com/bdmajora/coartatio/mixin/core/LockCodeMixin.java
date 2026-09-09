package com.bdmajora.coartatio.mixin.core;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.LockCode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Reuses LockCode.EMPTY_CODE instead of allocating an empty lock per tile entity. From LoliASM
// Vanilla defines EMPTY_CODE for exactly this and then misses one path: fromNBT returns EMPTY_CODE when the
// Lock tag is ABSENT, but allocates new LockCode("") when the tag is present and empty — which is what every
// lockable tile entity that has ever been saved actually writes
// So every chest, furnace, hopper, dispenser, dropper, brewing stand and beacon in a world gets its own empty
// LockCode plus its own empty String on load. Trivial once; not trivial times the tile entity count of a large
// world
// This is a vanilla oversight rather than a tradeoff: the constant exists and the two paths are meant to agree
@Mixin(LockCode.class)
public abstract class LockCodeMixin {
    @Shadow
    @Final
    public static LockCode EMPTY_CODE;

    // Cancelled at HEAD so vanilla's allocation never happens
    // Only the present-and-empty case is intercepted; the absent case already returns EMPTY_CODE on its own, and
    // a non-empty lock genuinely needs its own instance
    @Inject(method = "fromNBT", at = @At("HEAD"), cancellable = true)
    private static void coartatio$shareEmptyLock(NBTTagCompound nbt, CallbackInfoReturnable<LockCode> cir) {
        // 8 is the NBT type id for a string; passing it means a Lock tag of some other type falls through to
        // vanilla rather than being misread here
        if (nbt.hasKey("Lock", 8) && nbt.getString("Lock").isEmpty()) {
            cir.setReturnValue(EMPTY_CODE);
        }
    }
}
