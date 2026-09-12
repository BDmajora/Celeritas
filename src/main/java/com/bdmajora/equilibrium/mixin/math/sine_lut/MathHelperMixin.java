package com.bdmajora.equilibrium.mixin.math.sine_lut;

import com.bdmajora.equilibrium.common.util.math.CompactSineLUT;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Swaps vanilla's 256 KB sine table for CompactSineLUT's 64 KB one and nulls SIN_TABLE, since the cache footprint is the point; only the two overwritten methods read it, and ModCompatibility disables this when BetterFps' transformer would still read the field
@Mixin(MathHelper.class)
public class MathHelperMixin {
    @Shadow
    @Final
    @Mutable
    private static float[] SIN_TABLE;

    // Builds the compact table then drops vanilla's, which nothing has read yet at this point
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void onClassInit(CallbackInfo ci) {
        CompactSineLUT.init(SIN_TABLE);

        SIN_TABLE = null;
    }

    // Overwrite: through the compact table
    @Overwrite
    public static float sin(float value) {
        return CompactSineLUT.sin(value);
    }

    // Overwrite: through the compact table
    @Overwrite
    public static float cos(float value) {
        return CompactSineLUT.cos(value);
    }
}
