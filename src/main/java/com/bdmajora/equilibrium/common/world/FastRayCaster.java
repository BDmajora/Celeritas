package com.bdmajora.equilibrium.common.world;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;

// an allocation-free transcription of World#rayTraceBlocks
// vanilla's traversal allocates a Vec3d and a BlockPos on every one of its up-to-200 steps, and
// resolves a chunk from scratch for each block it looks at
// ray casting is not a rare operation - it is what decides whether a mob can see a player, where a
// player is looking, whether an arrow hit, and how much of an explosion reaches an entity - so those
// allocations add up to a meaningful share of the young generation on a busy server
// the traversal itself is transcribed rather than replaced: it is an unusual DDA with several fixups
// that look accidental - the -0.0 correction, the one-block back-off when the ray crosses a positive
// face - and it is not, because those are what make block selection agree between client and server
// changing any of them would move where players' crosshairs land
// the only changes here are that the running position is held in three doubles instead of a vector,
// that positions and vectors are materialised only at the point they are handed to block code, and
// that block states come from a ChunkSectionCursor
// one allocation vanilla makes is genuinely dropped rather than deferred: the MISS result built for
// every non-colliding block along the ray, which vanilla constructs unconditionally and then discards
// unless returnLastUncollidableBlock was requested - here it is only built when the caller asked for it
public final class FastRayCaster {
    private FastRayCaster() {
    }

    // matches World#rayTraceBlocks(Vec3d, Vec3d, boolean, boolean, boolean), with a cursor of its own
    @Nullable
    public static RayTraceResult rayTraceBlocks(World world, Vec3d start, Vec3d end, boolean stopOnLiquid,
                                                boolean ignoreBlockWithoutBoundingBox,
                                                boolean returnLastUncollidableBlock) {
        if (Double.isNaN(start.x) || Double.isNaN(start.y) || Double.isNaN(start.z)) {
            return null;
        }

        if (Double.isNaN(end.x) || Double.isNaN(end.y) || Double.isNaN(end.z)) {
            return null;
        }

        // Loading, because World#getBlockState loads. A ray that stopped at the edge of the loaded
        // area would let mobs see through unloaded terrain.
        ChunkSectionCursor cursor = new ChunkSectionCursor(world, true);

        return trace(world, cursor, start, end, stopOnLiquid, ignoreBlockWithoutBoundingBox,
                returnLastUncollidableBlock);
    }

    // the same traversal against a caller-supplied cursor
    // explosion exposure fires dozens of rays that all converge on the same point, so they cross mostly
    // the same blocks - sharing one cursor between them is where most of that win comes from
    @Nullable
    public static RayTraceResult trace(World world, ChunkSectionCursor cursor, Vec3d start, Vec3d end,
                                       boolean stopOnLiquid, boolean ignoreBlockWithoutBoundingBox,
                                       boolean returnLastUncollidableBlock) {
        int endX = MathHelper.floor(end.x);
        int endY = MathHelper.floor(end.y);
        int endZ = MathHelper.floor(end.z);

        int x = MathHelper.floor(start.x);
        int y = MathHelper.floor(start.y);
        int z = MathHelper.floor(start.z);

        double curX = start.x;
        double curY = start.y;
        double curZ = start.z;

        // The block the ray starts inside is tested before any stepping happens.
        {
            IBlockState state = cursor.getBlockState(x, y, z);
            BlockPos pos = new BlockPos(x, y, z);

            if (!ignoreBlockWithoutBoundingBox
                    || state.getCollisionBoundingBox(world, pos) != Block.NULL_AABB) {
                if (state.getBlock().canCollideCheck(state, stopOnLiquid)) {
                    RayTraceResult hit = state.collisionRayTrace(world, pos, start, end);

                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }

        RayTraceResult lastUncollidable = null;

        for (int remaining = 200; remaining-- >= 0; ) {
            if (Double.isNaN(curX) || Double.isNaN(curY) || Double.isNaN(curZ)) {
                return null;
            }

            if (x == endX && y == endY && z == endZ) {
                return returnLastUncollidableBlock ? lastUncollidable : null;
            }

            boolean crossesX = true;
            boolean crossesY = true;
            boolean crossesZ = true;

            double planeX = 999.0D;
            double planeY = 999.0D;
            double planeZ = 999.0D;

            if (endX > x) {
                planeX = x + 1.0D;
            } else if (endX < x) {
                planeX = x;
            } else {
                crossesX = false;
            }

            if (endY > y) {
                planeY = y + 1.0D;
            } else if (endY < y) {
                planeY = y;
            } else {
                crossesY = false;
            }

            if (endZ > z) {
                planeZ = z + 1.0D;
            } else if (endZ < z) {
                planeZ = z;
            } else {
                crossesZ = false;
            }

            double tX = 999.0D;
            double tY = 999.0D;
            double tZ = 999.0D;

            double deltaX = end.x - curX;
            double deltaY = end.y - curY;
            double deltaZ = end.z - curZ;

            if (crossesX) {
                tX = (planeX - curX) / deltaX;
            }

            if (crossesY) {
                tY = (planeY - curY) / deltaY;
            }

            if (crossesZ) {
                tZ = (planeZ - curZ) / deltaZ;
            }

            // Negative zero would compare as the smallest candidate and stall the ray in place;
            // nudging it negative forces progress. Vanilla's fixup, kept exactly.
            if (tX == -0.0D) {
                tX = -1.0E-4D;
            }

            if (tY == -0.0D) {
                tY = -1.0E-4D;
            }

            if (tZ == -0.0D) {
                tZ = -1.0E-4D;
            }

            EnumFacing facing;

            if (tX < tY && tX < tZ) {
                facing = endX > x ? EnumFacing.WEST : EnumFacing.EAST;
                curX = planeX;
                curY += deltaY * tX;
                curZ += deltaZ * tX;
            } else if (tY < tZ) {
                facing = endY > y ? EnumFacing.DOWN : EnumFacing.UP;
                curX += deltaX * tY;
                curY = planeY;
                curZ += deltaZ * tY;
            } else {
                facing = endZ > z ? EnumFacing.NORTH : EnumFacing.SOUTH;
                curX += deltaX * tZ;
                curY += deltaY * tZ;
                curZ = planeZ;
            }

            // Landing exactly on a positive face puts floor() in the next block along, so step back.
            x = MathHelper.floor(curX) - (facing == EnumFacing.EAST ? 1 : 0);
            y = MathHelper.floor(curY) - (facing == EnumFacing.UP ? 1 : 0);
            z = MathHelper.floor(curZ) - (facing == EnumFacing.SOUTH ? 1 : 0);

            IBlockState state = cursor.getBlockState(x, y, z);

            // Air is the overwhelming majority of what a ray crosses and can never collide, so it is
            // worth answering before anything is allocated for it. Not taken when the caller asked
            // for the last uncollidable block: vanilla records air positions as misses, and a caller
            // that wants those wants the air ones too.
            if (!returnLastUncollidableBlock && state.getMaterial() == Material.AIR) {
                continue;
            }

            if (ignoreBlockWithoutBoundingBox
                    && state.getMaterial() != Material.PORTAL
                    && state.getCollisionBoundingBox(world, new BlockPos(x, y, z)) == Block.NULL_AABB) {
                continue;
            }

            if (state.getBlock().canCollideCheck(state, stopOnLiquid)) {
                RayTraceResult hit = state.collisionRayTrace(world, new BlockPos(x, y, z),
                        new Vec3d(curX, curY, curZ), end);

                if (hit != null) {
                    return hit;
                }
            } else if (returnLastUncollidableBlock) {
                // Vanilla builds this whether or not anyone asked for it, then throws it away.
                lastUncollidable = new RayTraceResult(RayTraceResult.Type.MISS,
                        new Vec3d(curX, curY, curZ), facing, new BlockPos(x, y, z));
            }
        }

        return returnLastUncollidableBlock ? lastUncollidable : null;
    }
}
