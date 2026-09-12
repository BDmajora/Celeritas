package com.bdmajora.equilibrium.mixin.alloc.enum_values.piston_handler;

import net.minecraft.block.state.BlockPistonStructureHelper;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Stops the piston structure resolver cloning the facing array once per moved block along the twelve-block push limit; same EnumFacing.VALUES substitution as piston_block
@Mixin(BlockPistonStructureHelper.class)
public class BlockPistonStructureHelperMixin {
    @Redirect(
            method = "addBranchingBlocks",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;")
    )
    private EnumFacing[] equilibrium$reuseFacingArray() {
        return EnumFacing.VALUES;
    }
}
