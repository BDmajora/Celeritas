package com.bdmajora.equilibrium.mixin.world.inline_height;

import com.bdmajora.equilibrium.common.world.ChunkAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Removes the doubled chunk lookup from world height queries.
 *
 * <p>Same shape as {@code mixin.entity.fast_retrieval}: vanilla tests {@code isChunkLoaded} and then
 * fetches the chunk it just confirmed. Height is read by weather, by mob spawning, by sky-light
 * checks and by every {@code canSeeSky} call, so it is asked far more often than its simplicity
 * suggests.
 *
 * <p>The out-of-bounds branch is preserved exactly, including the odd asymmetry that vanilla returns
 * the sea level for coordinates outside the world border but zero for an unloaded chunk inside it.
 */
@Mixin(World.class)
public abstract class WorldMixin implements ChunkAccess {
    /**
     * @author JellySquid
     * @reason Resolve the chunk once rather than testing for it and then fetching it
     */
    @Overwrite
    public int getHeight(int x, int z) {
        World world = (World) (Object) this;

        int height;

        if (x >= -30000000 && z >= -30000000 && x < 30000000 && z < 30000000) {
            Chunk chunk = this.equilibrium$getLoadedChunk(x >> 4, z >> 4);

            height = chunk == null ? 0 : chunk.getHeightValue(x & 15, z & 15);
        } else {
            height = world.getSeaLevel() + 1;
        }

        return height;
    }
}
