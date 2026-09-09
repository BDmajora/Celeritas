package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.umbra.pipeline.VanillaFeatureToggles;
import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

// the "clouds = off | fast | fancy" shaders.properties toggle: lets the pack override the player's
// cloud video setting, which is Umbra's MixinOptions_CloudsOverride
// vanilla's own renderDistanceChunks >= 4 gate is mirrored ahead of the override - as Umbra does,
// and for the same reason: injecting at the head means the real check has not run yet, and a pack
// must not be able to force clouds on at a render distance where vanilla suppresses them
@Mixin(GameSettings.class)
public class GameSettingsCloudsMixin {
    @Shadow
    public int renderDistanceChunks;

    @Inject(method = "shouldRenderClouds", at = @At("HEAD"), cancellable = true)
    private void impetus$overrideCloudMode(CallbackInfoReturnable<Integer> cir) {
        if (this.renderDistanceChunks < 4) {
            return;
        }

        OptionalInt mode = VanillaFeatureToggles.getCloudMode();
        if (mode.isPresent()) {
            cir.setReturnValue(mode.getAsInt());
        }
    }
}
