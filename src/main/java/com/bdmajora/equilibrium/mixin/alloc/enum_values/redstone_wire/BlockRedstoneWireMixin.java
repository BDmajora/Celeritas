package com.bdmajora.equilibrium.mixin.alloc.enum_values.redstone_wire;

import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops redstone wire from cloning the facing array when it notifies its neighbours.
 *
 * <p>{@code Enum#values()} returns a defensive copy, so each of these loops allocates a six-element
 * array it reads once and drops. {@code EnumFacing.VALUES} is the same array in the same order and is
 * only read here.
 *
 * <p>The third call site — the one inside {@code calculateCurrentChanges}, which is by far the
 * hottest — is deliberately <em>not</em> listed. {@code mixin.block.redstone_wire} replaces that whole
 * method with a version that already uses {@code EnumFacing.VALUES}, and Mixin refuses to let one
 * mixin inject into a method another mixin of equal priority has merged. Naming it here crashes the
 * game at {@code Bootstrap.registerBlocks} rather than degrading gracefully, which is exactly what it
 * did before this was split out.
 *
 * <p>The consequence is small and only affects a non-default configuration: with
 * {@code mixin.block.redstone_wire=false} and this option on, {@code calculateCurrentChanges} keeps
 * its array copy. Both options are on by default, and in that case the allocation is gone either way.
 */
@Mixin(BlockRedstoneWire.class)
public class BlockRedstoneWireMixin {
    @Redirect(
            method = {"notifyWireNeighborsOfStateChange", "breakBlock"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;")
    )
    private EnumFacing[] equilibrium$reuseFacingArray() {
        return EnumFacing.VALUES;
    }
}
