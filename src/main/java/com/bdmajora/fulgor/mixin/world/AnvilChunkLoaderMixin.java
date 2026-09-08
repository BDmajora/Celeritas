package com.bdmajora.fulgor.mixin.world;

import com.bdmajora.fulgor.api.ChunkLightingData;
import com.bdmajora.fulgor.api.LightingEngineProvider;
import com.bdmajora.fulgor.lighting.NeighborLightFlags;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Mixes into AnvilChunkLoader to persist the two lighting-state pieces Fulgor adds to a chunk: pending
// neighbour boundary checks, and whether the chunk was ever fully lit. Reuses vanilla's own
// LightPopulated tag (rather than a new one) so a world stays consistent moving between Fulgor and vanilla
@Mixin(AnvilChunkLoader.class)
public abstract class AnvilChunkLoaderMixin {
    // A second flush after ChunkProviderServer's; not redundant since autosave, world unload, and mods
    // calling the loader directly all bypass the provider
    @Inject(method = "saveChunk", at = @At("HEAD"))
    private void fulgor$flushBeforeSave(World world, Chunk chunk, CallbackInfo ci) {
        ((LightingEngineProvider) world).fulgor$getLightingEngine().processLightUpdates();
    }

    @Inject(method = "readChunkFromNBT", at = @At("RETURN"))
    private void fulgor$readLightingState(World world, NBTTagCompound compound,
                                          CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = cir.getReturnValue();

        NeighborLightFlags.read(chunk, compound);

        ((ChunkLightingData) chunk).fulgor$setLightInitialized(compound.getBoolean("LightPopulated"));
    }

    @Inject(method = "writeChunkToNBT", at = @At("RETURN"))
    private void fulgor$writeLightingState(Chunk chunk, World world, NBTTagCompound compound, CallbackInfo ci) {
        NeighborLightFlags.write(chunk, compound);

        compound.setBoolean("LightPopulated", ((ChunkLightingData) chunk).fulgor$isLightInitialized());
    }
}
