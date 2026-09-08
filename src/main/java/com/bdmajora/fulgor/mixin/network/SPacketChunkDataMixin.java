package com.bdmajora.fulgor.mixin.network;

import com.bdmajora.fulgor.api.LightingEngineProvider;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Mixes into SPacketChunkData to flush pending light updates before the chunk is serialized; the packet
// copies light arrays straight out of the sections, so anything still queued would be silently omitted
// Hooked on calculateChunkSize (the constructor's first call) rather than the constructor itself, since
// a constructor injection can only fire at RETURN, after the packet is already built
@Mixin(SPacketChunkData.class)
public abstract class SPacketChunkDataMixin {
    @Inject(method = "calculateChunkSize", at = @At("HEAD"))
    private void fulgor$flushBeforeSerialize(Chunk chunk, boolean hasSkyLight, int changedSectionFilter,
                                             CallbackInfoReturnable<Integer> cir) {
        ((LightingEngineProvider) chunk).fulgor$getLightingEngine().processLightUpdates();
    }
}
