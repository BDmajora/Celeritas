package com.bdmajora.equilibrium.mixin.alloc.enum_values.piston_block;

import net.minecraft.block.BlockPistonBase;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Stops the piston's power check cloning the facing array twice per call (Enum#values() copies); shouldBeExtended runs on every neighbour update, and EnumFacing.VALUES is the same never-mutated array
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
