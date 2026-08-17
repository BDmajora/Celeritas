package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.iris.Iris;
import net.minecraft.client.renderer.OpenGlHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps the fixed-function lightmap coordinate inside the range shader packs are written against.
 * <p>
 * A lightmap texcoord is a raw block/sky pair in {@code [0, 240]}; the texture matrix vanilla installs turns it into
 * {@code (v + 8) / 256}, so {@code 240} is the brightest legal value. 1.12 nevertheless has an idiom for "full
 * bright" that pushes {@code 61680} ({@code 0xF0F0}) straight through, relying on {@code GL_CLAMP} to pin the
 * <em>texture lookup</em> to the last texel — harmless without shaders, catastrophic with them, because a pack reads
 * the coordinate arithmetically and gets {@code 240.97} where it expected at most {@code 0.97}. Packs commonly raise
 * the block-light term to a power, so the error lands in the hundreds of thousands, overflows a floating-point
 * colortex and paints an Inf/NaN pixel wrapped in a huge bloom flare.
 * <p>
 * The three vanilla users of the idiom are the eyes overlay layers, and those are handled properly (with the correct
 * program) by {@link LayerSpiderEyesMixin} and friends. This is the guard for everyone else: the same idiom is
 * copy-pasted throughout modded 1.12 entity renderers, and there is no sane reading of an out-of-range coordinate
 * other than "as bright as the lightmap goes".
 * <p>
 * Only while a pipeline is active. It is a visual no-op for vanilla anyway — with {@code GL_CLAMP} plus linear
 * filtering, {@code 240} and {@code 61680} both resolve to the centre of the last texel — but there is no reason to
 * put the branch in front of unshaded rendering.
 */
@Mixin(OpenGlHelper.class)
public class OpenGlHelperLightmapClampMixin {
    /** The raw coordinate of the brightest lightmap texel: 15 light levels x 16. */
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
        if (coord <= MAX_LIGHTMAP_COORD || Iris.getRenderingPipeline() == null) {
            return coord;
        }
        return MAX_LIGHTMAP_COORD;
    }
}
