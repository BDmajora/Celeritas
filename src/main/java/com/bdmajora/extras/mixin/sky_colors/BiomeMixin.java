package com.bdmajora.extras.mixin.sky_colors;

import com.bdmajora.extras.Extras;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Removes biome variation from the sky colour.
 *
 * <p>{@code World.getSkyColorBody} blends each nearby biome's {@link Biome#getSkyColorByTemp(float)}.
 * Forcing the temperature to a single value makes every biome produce the same sky, while leaving
 * vanilla's time-of-day and weather shading exactly as it was — which is the point: this is a "stop
 * the sky changing colour when I walk into a desert" switch, not a "turn the sky off" one.
 */
@Mixin(Biome.class)
public class BiomeMixin {
    /** Plains temperature; yields the standard overworld blue. */
    @Unique
    private static final float IMPETUS$UNIFORM_TEMPERATURE = 0.8F;

    @ModifyVariable(method = "getSkyColorByTemp", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float impetus$uniformSkyColor(float temperature) {
        if (!Extras.options().detail.skyColors) {
            return IMPETUS$UNIFORM_TEMPERATURE;
        }
        return temperature;
    }
}
