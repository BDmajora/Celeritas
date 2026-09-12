package com.bdmajora.equilibrium.mixin.math.fast_util;

import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Random;

// Removes the array copies and modulo from getOpposite (byIndex divides an already-valid index) and random (values() clones the array twice per call); the public VALUES field is the same never-mutated array
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
