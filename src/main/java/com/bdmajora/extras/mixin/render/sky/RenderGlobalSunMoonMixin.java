package com.bdmajora.extras.mixin.render.sky;

import com.bdmajora.extras.Extras;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Separate switches for the sun and the moon discs.
 *
 * <p>Celeritas Extra merged these into one "Sun &amp; Moon" switch; Sodium Extra keeps them apart,
 * and so does this. Vanilla draws both through the same code with only the bound texture differing,
 * so the two are told apart by slicing {@code renderSky} between the texture field references — sun
 * from {@code SUN_TEXTURES} to {@code MOON_PHASES_TEXTURES}, moon from there to the star-brightness
 * call that begins the star pass.
 *
 * <p>Suppression finishes and resets the buffer instead of skipping the draw: {@code Tessellator}
 * has already been filled by the time the draw happens, and leaving it dirty would corrupt whatever
 * uses it next.
 */
@Mixin(RenderGlobal.class)
public class RenderGlobalSunMoonMixin {
    private static final String SUN_TEXTURES =
            "Lnet/minecraft/client/renderer/RenderGlobal;SUN_TEXTURES:Lnet/minecraft/util/ResourceLocation;";
    private static final String MOON_TEXTURES =
            "Lnet/minecraft/client/renderer/RenderGlobal;MOON_PHASES_TEXTURES:Lnet/minecraft/util/ResourceLocation;";
    private static final String STAR_BRIGHTNESS =
            "Lnet/minecraft/client/multiplayer/WorldClient;getStarBrightness(F)F";

    @WrapOperation(
            method = "renderSky(FI)V",
            slice = @Slice(
                    from = @At(value = "FIELD", target = SUN_TEXTURES),
                    to = @At(value = "FIELD", target = MOON_TEXTURES)
            ),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V")
    )
    private void impetus$drawSun(Tessellator tessellator, Operation<Void> original) {
        impetus$drawOrDiscard(tessellator, original, Extras.options().detail.sun);
    }

    @WrapOperation(
            method = "renderSky(FI)V",
            slice = @Slice(
                    from = @At(value = "FIELD", target = MOON_TEXTURES),
                    to = @At(value = "INVOKE", target = STAR_BRIGHTNESS)
            ),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V")
    )
    private void impetus$drawMoon(Tessellator tessellator, Operation<Void> original) {
        impetus$drawOrDiscard(tessellator, original, Extras.options().detail.moon);
    }

    @Unique
    private static void impetus$drawOrDiscard(Tessellator tessellator, Operation<Void> original, boolean enabled) {
        if (enabled) {
            original.call(tessellator);
        } else {
            tessellator.getBuffer().finishDrawing();
            tessellator.getBuffer().reset();
        }
    }
}
