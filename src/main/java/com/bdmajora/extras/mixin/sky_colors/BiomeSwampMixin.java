package com.bdmajora.extras.mixin.sky_colors;

import com.bdmajora.extras.Extras;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.ColorizerFoliage;
import net.minecraft.world.ColorizerGrass;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeSwamp;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OptiFine's Swamp Colors switch: drops the swamp's hard-coded green-grey grass and foliage tint
 * back to the ordinary temperature-and-rainfall colour every other biome uses.
 *
 * <p>{@code BiomeSwamp} overrides the base methods with literal colours (and, for grass, a noise
 * lookup) rather than adjusting the base result, so there is nothing to "undo" — the base
 * computation is reproduced here instead. Going through {@link ColorizerGrass} and
 * {@link ColorizerFoliage} rather than returning a constant is what keeps resource-pack colourmaps
 * working.
 */
@Mixin(BiomeSwamp.class)
public class BiomeSwampMixin {
    @Inject(method = "getGrassColorAtPos", at = @At("HEAD"), cancellable = true)
    private void impetus$grassColor(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!Extras.options().detail.swampColors) {
            Biome self = (Biome) (Object) this;
            cir.setReturnValue(ColorizerGrass.getGrassColor(
                    impetus$temperature(self, pos), impetus$rainfall(self)));
        }
    }

    @Inject(method = "getFoliageColorAtPos", at = @At("HEAD"), cancellable = true)
    private void impetus$foliageColor(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!Extras.options().detail.swampColors) {
            Biome self = (Biome) (Object) this;
            cir.setReturnValue(ColorizerFoliage.getFoliageColor(
                    impetus$temperature(self, pos), impetus$rainfall(self)));
        }
    }

    @Unique
    private static double impetus$temperature(Biome biome, BlockPos pos) {
        return MathHelper.clamp(biome.getTemperature(pos), 0.0F, 1.0F);
    }

    @Unique
    private static double impetus$rainfall(Biome biome) {
        return MathHelper.clamp(biome.getRainfall(), 0.0F, 1.0F);
    }
}
