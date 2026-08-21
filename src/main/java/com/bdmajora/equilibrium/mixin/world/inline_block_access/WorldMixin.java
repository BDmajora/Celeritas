package com.bdmajora.equilibrium.mixin.world.inline_block_access;

import com.bdmajora.equilibrium.common.world.ChunkAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Routes every chunk lookup in the world through the cache that {@code mixin.util.chunk_access}
 * installed.
 *
 * <p>{@code World#getChunk(int, int)} is the single funnel that {@code getBlockState},
 * {@code getTileEntity}, {@code getLightFor}, {@code isAirBlock} and everything else eventually
 * reaches. Replacing it here rather than each of those means one small overwrite instead of a dozen
 * large ones, and — more importantly — it means mods injecting into {@code getBlockState} keep
 * working, because that method is untouched.
 *
 * <p>The body it replaces is a single delegation to the chunk provider. Everything the provider would
 * have done still happens on a miss; the only change is that a hit does not go there at all.
 */
@Mixin(World.class)
public abstract class WorldMixin implements ChunkAccess {
    /**
     * @author JellySquid
     * @reason Resolve chunks through the direct cache rather than the provider's map
     */
    @Overwrite
    public Chunk getChunk(int chunkX, int chunkZ) {
        return this.equilibrium$getChunkCached(chunkX, chunkZ);
    }
}
