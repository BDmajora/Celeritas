package com.bdmajora.extras.mixin.render.fog;

import com.bdmajora.extras.Extras;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.world.WorldProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Removes the darkening applied near the bottom of the world.
 *
 * <p>1.12.2 folds void fog into {@code EntityRenderer.updateFogColor} through
 * {@link WorldProvider#getVoidFogYFactor()}. Returning 1.0 makes the game treat the player as fully
 * above the void, which skips the effect without touching anything else that method computes.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererVoidFogMixin {
    @Redirect(
            method = "updateFogColor(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldProvider;getVoidFogYFactor()D")
    )
    private double impetus$voidFogFactor(WorldProvider provider) {
        if (!Extras.options().detail.voidFog) {
            return 1.0D;
        }
        return provider.getVoidFogYFactor();
    }
}
