package com.bdmajora.extras.mixin.misc;

import com.bdmajora.extras.client.TimeWeatherOverride;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Applies the Time and Weather locks to the integrated server, at tick HEAD before vanilla's weather scheduler runs,
// so a forced weather state isn't immediately overwritten by vanilla's own countdown
// Impetus is client-only, so this only reaches single-player worlds, same as OptiFine's version
@Mixin(WorldServer.class)
public class WorldServerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void impetus$applyOverrides(CallbackInfo ci) {
        TimeWeatherOverride.apply((WorldServer) (Object) this);
    }
}
