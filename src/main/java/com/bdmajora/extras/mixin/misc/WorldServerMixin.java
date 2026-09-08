package com.bdmajora.extras.mixin.misc;

import com.bdmajora.extras.client.TimeWeatherOverride;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the Time and Weather locks to the integrated server.
 *
 * <p>At the head of the world tick, before vanilla's own weather scheduler runs, so a forced weather
 * state is not immediately overwritten by the countdown vanilla was about to decrement.
 *
 * <p>Impetus is client-only, so this reaches single-player worlds and nothing else — the same limit
 * OptiFine's version has, and for the same reason: there is no world here to change on a dedicated
 * server.
 */
@Mixin(WorldServer.class)
public class WorldServerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void impetus$applyOverrides(CallbackInfo ci) {
        TimeWeatherOverride.apply((WorldServer) (Object) this);
    }
}
