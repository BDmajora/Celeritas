package com.bdmajora.equilibrium.mixin.math.fast_util;

import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Random;

/**
 * Removes the array copies and modulo arithmetic from the two most-called {@link EnumFacing}
 * helpers.
 *
 * <p>{@code getOpposite} routes through {@code byIndex}, which computes
 * {@code MathHelper.abs(index % VALUES.length)} — a division on a value that is already a valid
 * index, stored in the enum precisely so it would not need computing.
 *
 * <p>{@code random} is worse: it calls {@code values()} twice, and {@code values()} on a Java enum
 * clones the backing array every time. Two six-element allocations per call, on a method that block
 * ticking, mob AI and particle spawning all reach for. The public {@code VALUES} field holds the same
 * array in the same order and is never handed out for mutation.
 */
@Mixin(EnumFacing.class)
public class EnumFacingMixin {
    @Shadow
    @Final
    public static EnumFacing[] VALUES;

    @Shadow
    @Final
    private int opposite;

    /**
     * @author JellySquid
     * @reason Avoid the modulo and abs operations
     */
    @Overwrite
    public EnumFacing getOpposite() {
        return VALUES[this.opposite];
    }

    /**
     * @author JellySquid
     * @reason Do not allocate an excessive number of EnumFacing arrays
     */
    @Overwrite
    public static EnumFacing random(Random rand) {
        return VALUES[rand.nextInt(VALUES.length)];
    }
}
