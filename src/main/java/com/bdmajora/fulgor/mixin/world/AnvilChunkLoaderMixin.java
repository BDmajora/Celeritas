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

/**
 * Persists the two pieces of lighting state Fulgor adds to a chunk.
 *
 * <p>Both have to survive a save: a chunk can be unloaded still owing its neighbours boundary checks,
 * and a chunk that was never fully lit must not come back claiming it was.
 *
 * <p>{@code LightPopulated} is vanilla's own tag, reused rather than replaced, so a world moved between
 * Fulgor and no Fulgor keeps a consistent answer either way.
 */
@Mixin(AnvilChunkLoader.class)
public abstract class AnvilChunkLoaderMixin {
    /**
     * A second flush, after {@code ChunkProviderServer} already did one.
     *
     * <p>Not redundant: chunks are also written from autosave, from world unload and from mods calling
     * the loader directly, and none of those go through the provider.
     */
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
