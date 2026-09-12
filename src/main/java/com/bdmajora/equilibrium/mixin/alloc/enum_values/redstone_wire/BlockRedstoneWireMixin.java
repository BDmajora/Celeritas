package com.bdmajora.equilibrium.mixin.alloc.enum_values.redstone_wire;

import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Stops redstone wire cloning the facing array when notifying neighbours; the calculateCurrentChanges call site is deliberately NOT listed since mixin.block.redstone_wire replaces that whole method and Mixin crashes on a double merge, so with that option off the copy remains there
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
