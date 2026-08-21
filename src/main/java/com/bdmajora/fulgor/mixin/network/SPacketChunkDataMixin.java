package com.bdmajora.fulgor.mixin.network;

import com.bdmajora.fulgor.api.LightingEngineProvider;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flushes pending light before a chunk is serialised for the client.
 *
 * <p>The packet copies the light arrays out of the sections directly, so anything still queued would
 * simply not be in it — and the client has no way to discover that it received a stale answer.
 *
 * <p>Hooked on {@code calculateChunkSize} rather than the constructor because a constructor can only
 * be injected at its RETURN, by which point the packet has already been built. {@code calculateChunkSize}
 * is the first thing the constructor calls, which makes it the earliest reachable point.
 */
@Mixin(SPacketChunkData.class)
public abstract class SPacketChunkDataMixin {
    @Inject(method = "calculateChunkSize", at = @At("HEAD"))
    private void fulgor$flushBeforeSerialize(Chunk chunk, boolean hasSkyLight, int changedSectionFilter,
                                             CallbackInfoReturnable<Integer> cir) {
        ((LightingEngineProvider) chunk).fulgor$getLightingEngine().processLightUpdates();
    }
}
