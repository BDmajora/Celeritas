package com.bdmajora.equilibrium.mixin.world.explosions.entity_raycast;

import com.bdmajora.equilibrium.common.world.ChunkSectionCursor;
import com.bdmajora.equilibrium.common.world.FastRayCaster;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

// Shares one ChunkSectionCursor across all ~45 exposure rays of a single getBlockDensity call instead of resolving chunk/section per block per ray
// Sample grid arithmetic left exactly as vanilla wrote it, floating-point quirks included - the sample count depends on rounding and changing it would change explosion damage
@Mixin(World.class)
public abstract class WorldMixin {
    // Share one chunk cursor between every exposure ray of the same call
    @Overwrite
    public float getBlockDensity(Vec3d vec, AxisAlignedBB bb) {
        double stepX = 1.0D / ((bb.maxX - bb.minX) * 2.0D + 1.0D);
        double stepY = 1.0D / ((bb.maxY - bb.minY) * 2.0D + 1.0D);
        double stepZ = 1.0D / ((bb.maxZ - bb.minZ) * 2.0D + 1.0D);

        double offsetX = (1.0D - Math.floor(1.0D / stepX) * stepX) / 2.0D;
        double offsetZ = (1.0D - Math.floor(1.0D / stepZ) * stepZ) / 2.0D;

        // Written as a negated >= rather than a < so that a NaN step — which a degenerate bounding
        // box produces — still takes the bail-out branch, as it does in vanilla.
        if (!(stepX >= 0.0D) || !(stepY >= 0.0D) || !(stepZ >= 0.0D)) {
            return 0.0F;
        }

        World world = (World) (Object) this;

        // Loading, because World#getBlockState loads and vanilla's rays go through it.
        ChunkSectionCursor cursor = new ChunkSectionCursor(world, true);

        int clear = 0;
        int total = 0;

        for (float fx = 0.0F; fx <= 1.0F; fx = (float) (fx + stepX)) {
            for (float fy = 0.0F; fy <= 1.0F; fy = (float) (fy + stepY)) {
                for (float fz = 0.0F; fz <= 1.0F; fz = (float) (fz + stepZ)) {
                    double sampleX = bb.minX + (bb.maxX - bb.minX) * fx;
                    double sampleY = bb.minY + (bb.maxY - bb.minY) * fy;
                    double sampleZ = bb.minZ + (bb.maxZ - bb.minZ) * fz;

                    Vec3d sample = new Vec3d(sampleX + offsetX, sampleY, sampleZ + offsetZ);

                    if (FastRayCaster.trace(world, cursor, sample, vec, false, false, false) == null) {
                        ++clear;
                    }

                    ++total;
                }
            }
        }

        return (float) clear / (float) total;
    }
}
