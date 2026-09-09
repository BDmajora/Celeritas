package com.bdmajora.equilibrium.mixin.util.chunk_access;

import com.bdmajora.equilibrium.common.world.ChunkAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

// gives World a one-entry chunk cache and the ChunkAccess interface over it
// block reads arrive in runs - a collision test walks a box, an explosion ray walks a line, the
// light engine walks a neighbourhood - and consecutive reads in a run land in the same chunk almost
// every time, while vanilla answers each of them with a hash lookup in ChunkProviderServer's map
// remembering the last chunk turns the common case into three field comparisons
// the cache is validated rather than trusted, which is what makes it safe without synchronisation:
// a world is normally touched by one thread, but "normally" is not a guarantee on 1.12.2 - mods tick
// worlds off-thread, and Impetus' own chunk builder reads world state from the build threads
// a torn read cannot produce a wrong answer here because the only thing read racily is a single
// reference, and the coordinates it is checked against come from the object itself, so a stale or
// foreign chunk fails the comparison and the caller falls through to the provider - the worst
// outcome of a race is a cache miss
// unloadQueued is cleared on a cache hit because ChunkProviderServer.getLoadedChunk clears it, and
// that is not incidental: it is how the server learns a chunk is still in use and should not be
// dropped this tick, so a cache that skipped it would let chunks unload out from under the code
// reading them
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

        // The client's provider answers with a shared empty chunk at the origin when nothing is
        // loaded. Storing that would poison the cache for every subsequent lookup, so only a chunk
        // that agrees about where it is gets kept.
        if (chunk != null && chunk.x == chunkX && chunk.z == chunkZ) {
            this.equilibrium$lastChunk = chunk;
        }

        return chunk;
    }
}
