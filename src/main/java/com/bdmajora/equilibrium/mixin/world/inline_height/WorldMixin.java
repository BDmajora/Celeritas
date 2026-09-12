package com.bdmajora.equilibrium.mixin.world.inline_height;

import com.bdmajora.equilibrium.common.world.ChunkAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

// Removes the doubled chunk lookup from world height queries (isChunkLoaded then getChunk), asked by weather, spawning, sky-light and every canSeeSky; the out-of-bounds asymmetry (sea level outside the border, zero for unloaded inside) is preserved exactly
@Mixin(World.class)
public abstract class WorldMixin implements ChunkAccess {
    // Overwrite: reads the heightmap directly instead of going through getChunk
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
