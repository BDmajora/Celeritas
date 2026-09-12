package com.bdmajora.equilibrium.mixin.util.chunk_access;

import com.bdmajora.equilibrium.common.world.ChunkAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

// One-entry chunk cache on World behind ChunkAccess: reads arrive in runs landing in the same chunk, and the cache is validated by coordinates so a racy read (mods tick off-thread, the chunk builder reads world state) can only miss; unloadQueued is cleared on hit because getLoadedChunk does, and that is how the server learns a chunk is still in use
@Mixin(World.class)
public abstract class WorldMixin implements ChunkAccess {
    @Shadow
    protected IChunkProvider chunkProvider;

    @Unique
    private Chunk equilibrium$lastChunk;

    @Nullable
    @Override
    public Chunk equilibrium$getLoadedChunk(int chunkX, int chunkZ) {
        Chunk cached = this.equilibrium$lastChunk;

        if (cached != null && cached.x == chunkX && cached.z == chunkZ && cached.isLoaded()) {
            cached.unloadQueued = false;
            return cached;
        }

        Chunk chunk = this.chunkProvider.getLoadedChunk(chunkX, chunkZ);

        if (chunk != null) {
            this.equilibrium$lastChunk = chunk;
        }

        return chunk;
    }

    @Override
    public Chunk equilibrium$getChunkCached(int chunkX, int chunkZ) {
        Chunk cached = this.equilibrium$lastChunk;

        if (cached != null && cached.x == chunkX && cached.z == chunkZ && cached.isLoaded()) {
            cached.unloadQueued = false;
            return cached;
        }

        Chunk chunk = this.chunkProvider.provideChunk(chunkX, chunkZ);

        // The client's provider answers with a shared empty chunk at the origin when nothing is loaded; storing that would poison the cache, so only a chunk that agrees about where it is gets kept
        if (chunk != null && chunk.x == chunkX && chunk.z == chunkZ) {
            this.equilibrium$lastChunk = chunk;
        }

        return chunk;
    }
}
