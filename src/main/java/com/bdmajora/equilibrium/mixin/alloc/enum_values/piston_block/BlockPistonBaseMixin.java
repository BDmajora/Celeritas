package com.bdmajora.equilibrium.mixin.alloc.enum_values.piston_block;

import net.minecraft.block.BlockPistonBase;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops the piston's power check from cloning the facing array twice per call.
 *
 * <p>{@code Enum#values()} returns a defensive copy, so every one of these loops allocates a
 * six-element array it reads once and drops. {@code shouldBeExtended} runs for every piston on every
 * neighbour update, which in a redstone build means constantly.
 *
 * <p>{@code EnumFacing.VALUES} is the same array in the same order, is public, and is never handed
 * out for mutation by vanilla — the loops here only read it.
 */
@Mixin(BlockPistonBase.class)
public class BlockPistonBaseMixin {
    @Redirect(
            method = "shouldBeExtended",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;")
    )
    private EnumFacing[] equilibrium$reuseFacingArray() {
        return EnumFacing.VALUES;
    }
}
