package com.bdmajora.extras.mixin.render.sky;

import com.bdmajora.extras.Extras;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Star visibility and star count.
 *
 * <p>Visibility goes through the brightness value rather than the draw call: returning zero makes
 * vanilla skip the star pass on its own, so the geometry never has to be rebuilt and the switch is
 * free to flip at any time.
 *
 * <p>The count is a constant swap inside the generator, which leaves both the VBO and display-list
 * build paths intact. It only takes effect on a renderer reload, which is why the option carries
 * {@code REQUIRES_RENDERER_RELOAD}.
 */
@Mixin(RenderGlobal.class)
public class RenderGlobalStarsMixin {
    @WrapOperation(
            method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/WorldClient;getStarBrightness(F)F")
    )
    private float impetus$starBrightness(WorldClient world, float partialTicks, Operation<Float> original) {
        if (!Extras.options().detail.stars) {
            return 0.0F;
        }
        return original.call(world, partialTicks);
    }

    @ModifyConstant(
            method = "renderStars(Lnet/minecraft/client/renderer/BufferBuilder;)V",
            constant = @Constant(intValue = 1500)
    )
    private int impetus$starCount(int vanillaCount) {
        return Extras.options().detail.totalStars;
    }
}
