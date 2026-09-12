package com.bdmajora.equilibrium.mixin.math.fast_util;

import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Random;

// removes the array copies and modulo arithmetic from the two most-called EnumFacing helpers
// getOpposite routes through byIndex, which computes MathHelper.abs(index % VALUES.length) - a
// division on a value that is already a valid index, stored in the enum precisely so it would not
// need computing
// random is worse: it calls values() twice, and values() on a Java enum clones the backing array
// every time, so that is two six-element allocations per call on a method that block ticking, mob AI
// and particle spawning all reach for
// the public VALUES field holds the same array in the same order and is never handed out for mutation
@Mixin(EnumFacing.class)
public class EnumFacingMixin {
    @Shadow
    @Final
    public static EnumFacing[] VALUES;

    @Shadow
    @Final
    private int opposite;

    // Overwrite: precomputed table instead of a values() lookup
    @Overwrite
    public EnumFacing getOpposite() {
        return VALUES[this.opposite];
    }

    // Overwrite: indexes the cached values array instead of cloning it
    @Overwrite
    public static EnumFacing random(Random rand) {
        return VALUES[rand.nextInt(VALUES.length)];
    }
}
