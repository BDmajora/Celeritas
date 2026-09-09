package com.bdmajora.extras.mixin.biome_colors;

import com.bdmajora.extras.Extras;
import net.minecraft.world.biome.BiomeColorHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// replaces biome grass, foliage and water tint with fixed colours
// the saving is not the lookup itself but the blend around it: these methods average the tint across
// every biome within the blend radius, which is up to 225 samples per block face, and returning a
// constant skips all of it at the cost of every biome looking like plains
// 1.20's equivalent is BiomeColors; on 1.12.2 it is BiomeColorHelper
@Mixin(BiomeColorHelper.class)
public class BiomeColorHelperMixin {
    @Unique
    private static final int IMPETUS$DEFAULT_GRASS_COLOR = 0x91BD59;
    @Unique
    private static final int IMPETUS$DEFAULT_WATER_COLOR = 0xFFFFFF;
    @Unique
    private static final int IMPETUS$DEFAULT_FOLIAGE_COLOR = 0x59AE30;

    @Inject(method = "getGrassColorAtPos", at = @At("HEAD"), cancellable = true)
    private static void impetus$grassColor(CallbackInfoReturnable<Integer> cir) {
        if (!Extras.options().detail.biomeColors) {
            cir.setReturnValue(IMPETUS$DEFAULT_GRASS_COLOR);
        }
    }

    @Inject(method = "getWaterColorAtPos", at = @At("HEAD"), cancellable = true)
    private static void impetus$waterColor(CallbackInfoReturnable<Integer> cir) {
        if (!Extras.options().detail.biomeColors) {
            cir.setReturnValue(IMPETUS$DEFAULT_WATER_COLOR);
        }
    }

    @Inject(method = "getFoliageColorAtPos", at = @At("HEAD"), cancellable = true)
    private static void impetus$foliageColor(CallbackInfoReturnable<Integer> cir) {
        if (!Extras.options().detail.biomeColors) {
            cir.setReturnValue(IMPETUS$DEFAULT_FOLIAGE_COLOR);
        }
    }
}
