package com.bdmajora.fulgor.mixin.world;

import com.bdmajora.fulgor.api.LightingEngineProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flushes pending light before the server writes chunks out or lets them go.
 *
 * <p>Deferral is only safe as long as every path that reads light goes through the engine. Saving does
 * not — it copies the light arrays straight out of the sections — and unloading removes the chunk the
 * pending updates were going to be applied to. Both are flushed here rather than being made
 * engine-aware, because both are rare and neither is on a hot path.
 */
@Mixin(ChunkProviderServer.class)
public abstract class ChunkProviderServerMixin {
    @Shadow
    @Final
    public WorldServer world;

    @Inject(method = "saveChunks", at = @At("HEAD"))
    private void fulgor$flushBeforeSave(boolean all, CallbackInfoReturnable<Boolean> cir) {
        ((LightingEngineProvider) this.world).fulgor$getLightingEngine().processLightUpdates();
    }

    /**
     * Also flushes once per tick, which is not strictly required but keeps the queues from growing
     * across a long stretch of generation with nothing reading light.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void fulgor$flushBeforeUnload(CallbackInfoReturnable<Boolean> cir) {
        ((LightingEngineProvider) this.world).fulgor$getLightingEngine().processLightUpdates();
    }
}
