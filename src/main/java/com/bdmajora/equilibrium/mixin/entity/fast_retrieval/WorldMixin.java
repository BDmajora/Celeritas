package com.bdmajora.equilibrium.mixin.entity.fast_retrieval;

import com.bdmajora.equilibrium.common.world.ChunkAccess;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Halves the chunk lookups an entity query performs.
 *
 * <p>Vanilla asks {@code isChunkLoaded} and then, if the answer was yes, asks {@code getChunk} — two
 * traversals of the chunk provider for every chunk column the query box touches. The second one
 * always finds what the first one just found.
 *
 * <p>Asking once for the chunk and treating null as "not loaded" is the same test and the same
 * answer. On the client the two differ in one respect worth stating: vanilla's {@code getChunk} hands
 * back a shared empty chunk when nothing is loaded, and an empty chunk contributes no entities, so
 * skipping it produces the same list.
 *
 * <p>This is on the path of every explosion, every mob's target search, every item pickup and every
 * collision test that involves another entity, so the doubled lookup is paid a great many times per
 * tick.
 */
@Mixin(World.class)
public abstract class WorldMixin implements ChunkAccess {
    /**
     * @author JellySquid
     * @reason Resolve each chunk once rather than testing for it and then fetching it
     */
    @Overwrite
    public List<Entity> getEntitiesInAABBexcluding(@Nullable Entity entityIn, AxisAlignedBB boundingBox,
                                                   @Nullable Predicate<? super Entity> predicate) {
        List<Entity> entities = Lists.newArrayList();

        int minChunkX = MathHelper.floor((boundingBox.minX - World.MAX_ENTITY_RADIUS) / 16.0D);
        int maxChunkX = MathHelper.floor((boundingBox.maxX + World.MAX_ENTITY_RADIUS) / 16.0D);
        int minChunkZ = MathHelper.floor((boundingBox.minZ - World.MAX_ENTITY_RADIUS) / 16.0D);
        int maxChunkZ = MathHelper.floor((boundingBox.maxZ + World.MAX_ENTITY_RADIUS) / 16.0D);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                Chunk chunk = this.equilibrium$getLoadedChunk(chunkX, chunkZ);

                if (chunk != null) {
                    chunk.getEntitiesWithinAABBForEntity(entityIn, boundingBox, entities, predicate);
                }
            }
        }

        return entities;
    }
}
