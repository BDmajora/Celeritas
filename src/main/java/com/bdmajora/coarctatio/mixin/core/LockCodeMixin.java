package com.bdmajora.coarctatio.mixin.core;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.LockCode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Reuses LockCode.EMPTY_CODE instead of allocating an empty lock per tile entity (from LoliASM): vanilla's fromNBT allocates new LockCode("") for a present-but-empty Lock tag, which every saved lockable tile entity writes
@Mixin(LockCode.class)
public abstract class LockCodeMixin {
    @Shadow
    @Final
    public static LockCode EMPTY_CODE;

    // Cancelled at HEAD so vanilla's allocation never happens; only the present-and-empty case is intercepted, absent already returns EMPTY_CODE and non-empty needs its own instance
    @Inject(method = "fromNBT", at = @At("HEAD"), cancellable = true)
    private static void coarctatio$shareEmptyLock(NBTTagCompound nbt, CallbackInfoReturnable<LockCode> cir) {
        // 8 is the NBT string type id, so a Lock tag of another type falls through to vanilla rather than being misread
        if (nbt.hasKey("Lock", 8) && nbt.getString("Lock").isEmpty()) {
            cir.setReturnValue(EMPTY_CODE);
        }
    }
}
