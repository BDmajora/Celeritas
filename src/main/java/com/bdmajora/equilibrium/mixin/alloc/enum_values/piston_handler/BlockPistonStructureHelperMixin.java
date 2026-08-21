package com.bdmajora.equilibrium.mixin.alloc.enum_values.piston_handler;

import net.minecraft.block.state.BlockPistonStructureHelper;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops the piston structure resolver from cloning the facing array once per moved block.
 *
 * <p>{@code addBlockLine} walks the twelve-block push limit and asks for the facings at each step, so
 * a full slime-block contraption firing allocates one array per block it moves, per fire. Same
 * substitution as {@code piston_block}: the shared, never-mutated {@code EnumFacing.VALUES}.
 */
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
