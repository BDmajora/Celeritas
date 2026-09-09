package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.umbra.Umbra;
import net.minecraft.client.renderer.OpenGlHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Clamps full-bright lightmap values while shaders are active.
@Mixin(OpenGlHelper.class)
public class OpenGlHelperLightmapClampMixin {
    // Brightest valid lightmap coordinate.
    private static final float MAX_LIGHTMAP_COORD = 240.0f;

    @ModifyVariable(method = "setLightmapTextureCoords(IFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 0,
            require = 0)
    private static float impetus$clampBlockLight(float blockLight) {
        return impetus$clamp(blockLight);
    }

    @ModifyVariable(method = "setLightmapTextureCoords(IFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 1,
            require = 0)
    private static float impetus$clampSkyLight(float skyLight) {
        return impetus$clamp(skyLight);
    }

    private static float impetus$clamp(float coord) {
        if (coord <= MAX_LIGHTMAP_COORD || Umbra.getRenderingPipeline() == null) {
            return coord;
        }
        return MAX_LIGHTMAP_COORD;
    }
}
