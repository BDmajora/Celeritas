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

// swaps vanilla's 256 KB sine table for CompactSineLUT's 64 KB one
// the table is handed over at the end of <clinit> and then dropped, and dropping it is not an
// afterthought: the whole point of the exercise is the cache footprint, and leaving a quarter of a
// megabyte of dead floats resident would give back most of what the compaction won on a machine whose
// L3 is already contested by a modded instance's live set
// SIN_TABLE is private in MathHelper and read by nothing but the two methods overwritten here, so
// nulling it cannot be observed
// the one way it could be is another mod replacing sin/cos with an implementation that still reads
// the field, which is exactly what BetterFps' math transformer does - ModCompatibility disables this
// whole option when BetterFps is installed rather than racing it for the field
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
