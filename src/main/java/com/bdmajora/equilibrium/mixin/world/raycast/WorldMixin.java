package com.bdmajora.equilibrium.mixin.world.raycast;

import com.bdmajora.equilibrium.common.world.FastRayCaster;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import javax.annotation.Nullable;

/**
 * Replaces the world's block ray tracer with {@link FastRayCaster}.
 *
 * <p>Only the five-argument form is overwritten — the two shorter ones delegate to it, so they
 * inherit the change without needing to be touched, and anything injecting into them keeps working.
 */
@Mixin(World.class)
public abstract class WorldMixin {
    /**
     * @author JellySquid
     * @reason Trace without allocating a vector and a block position per step
     */
    @Nullable
    @Overwrite
    public RayTraceResult rayTraceBlocks(Vec3d start, Vec3d end, boolean stopOnLiquid,
                                         boolean ignoreBlockWithoutBoundingBox,
                                         boolean returnLastUncollidableBlock) {
        return FastRayCaster.rayTraceBlocks((World) (Object) this, start, end, stopOnLiquid,
                ignoreBlockWithoutBoundingBox, returnLastUncollidableBlock);
    }
}
