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

// Flushes pending light before the server writes chunks out or lets them go; saving copies light arrays straight out of sections and unloading removes the chunk the updates targeted, and both are rare enough to flush here rather than make engine-aware
@Mixin(ChunkProviderServer.class)
public abstract class ChunkProviderServerMixin {
    @Shadow
    @Final
    public WorldServer world;

    @Inject(method = "saveChunks", at = @At("HEAD"))
    private void fulgor$flushBeforeSave(boolean all, CallbackInfoReturnable<Boolean> cir) {
        ((LightingEngineProvider) this.world).fulgor$getLightingEngine().processLightUpdates();
    }

    // Also flushes once per tick, not strictly required but keeps queues from growing across a long stretch of generation with nothing reading light
    @Inject(method = "tick", at = @At("HEAD"))
    private void fulgor$flushBeforeUnload(CallbackInfoReturnable<Boolean> cir) {
        ((LightingEngineProvider) this.world).fulgor$getLightingEngine().processLightUpdates();
    }
}
