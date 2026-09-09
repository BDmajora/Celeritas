package com.bdmajora.equilibrium.mixin.entity.collisions.movement;

import com.bdmajora.equilibrium.common.world.ChunkSectionCursor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import javax.annotation.Nullable;
import java.util.List;

// reads the blocks around a moving entity through a chunk section cursor
// this is the method behind every entity's movement, called several times per entity per tick - once
// to gather what the entity might hit, and again for each axis it is pushed along
// for a player it inspects a 4x5x4 region, for a fast minecart more, and vanilla resolves a chunk
// from the provider for each of those positions individually
// the loop nests x, then z, then y, so consecutive reads run *down a column* and sixteen of them
// share a chunk section; holding the section between reads turns the inner loop's chunk lookup into
// an array index
// that is the whole change: the iteration order, the bounds, the world border handling and both
// Forge collision hooks are exactly as vanilla wrote them
// the cursor is created in non-loading mode, matching vanilla: the column is tested with
// isBlockLoaded before anything inside it is read, so an entity walking towards ungenerated terrain
// treats it as empty rather than generating it - getting this backwards would let a fast-moving
// entity generate chunks ahead of itself
// vanilla's pooled mutable block position is kept for the same reason vanilla has it: the position is
// handed to addCollisionBoxToList and some blocks read it, so reusing one is both cheaper and closer
// to vanilla than allocating
@Mixin(World.class)
public abstract class WorldMixin {
    /**
     * @author JellySquid
     * @reason Resolve one chunk section per column rather than one chunk per block
     */
    @Overwrite
    private boolean getCollisionBoxes(@Nullable Entity entityIn, AxisAlignedBB aabb, boolean stopOnFirst,
                                      @Nullable List<AxisAlignedBB> outList) {
        int minX = MathHelper.floor(aabb.minX) - 1;
        int maxX = MathHelper.ceil(aabb.maxX) + 1;
        int minY = MathHelper.floor(aabb.minY) - 1;
        int maxY = MathHelper.ceil(aabb.maxY) + 1;
        int minZ = MathHelper.floor(aabb.minZ) - 1;
        int maxZ = MathHelper.ceil(aabb.maxZ) + 1;

        World world = (World) (Object) this;

        WorldBorder border = world.getWorldBorder();

        boolean wasOutsideBorder = entityIn != null && entityIn.isOutsideBorder();
        boolean isInsideBorder = entityIn != null && world.isInsideWorldBorder(entityIn);

        IBlockState borderFiller = Blocks.STONE.getDefaultState();

        BlockPos.PooledMutableBlockPos pos = BlockPos.PooledMutableBlockPos.retain();

        ChunkSectionCursor cursor = new ChunkSectionCursor(world, false);

        if (stopOnFirst && !net.minecraftforge.event.ForgeEventFactory.gatherCollisionBoxes(world, entityIn, aabb, outList)) {
            pos.release();
            return true;
        }

        try {
            for (int x = minX; x < maxX; ++x) {
                for (int z = minZ; z < maxZ; ++z) {
                    boolean onXEdge = x == minX || x == maxX - 1;
                    boolean onZEdge = z == minZ || z == maxZ - 1;

                    // Corners of the expanded box cannot touch the entity, so vanilla skips them.
                    if (onXEdge && onZEdge) {
                        continue;
                    }

                    if (!world.isBlockLoaded(pos.setPos(x, 64, z))) {
                        continue;
                    }

                    for (int y = minY; y < maxY; ++y) {
                        if ((onXEdge || onZEdge) && y == maxY - 1) {
                            continue;
                        }

                        if (stopOnFirst) {
                            if (x < -30000000 || x >= 30000000 || z < -30000000 || z >= 30000000) {
                                return true;
                            }
                        } else if (entityIn != null && wasOutsideBorder == isInsideBorder) {
                            entityIn.setOutsideBorder(!isInsideBorder);
                        }

                        pos.setPos(x, y, z);

                        IBlockState state;

                        // Outside the border, the world is treated as solid stone so entities cannot
                        // walk out of it — vanilla's rule, and the reason this is not just a read.
                        if (!stopOnFirst && !border.contains(pos) && isInsideBorder) {
                            state = borderFiller;
                        } else {
                            state = cursor.getBlockState(x, y, z);
                        }

                        state.addCollisionBoxToList(world, pos, aabb, outList, entityIn, false);

                        if (stopOnFirst && !net.minecraftforge.event.ForgeEventFactory.gatherCollisionBoxes(world, entityIn, aabb, outList)) {
                            return true;
                        }
                    }
                }
            }
        } finally {
            pos.release();
        }

        return !outList.isEmpty();
    }
}
