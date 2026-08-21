package com.bdmajora.equilibrium.mixin.math.fast_blockpos;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Replaces the six directional offset helpers with direct construction.
 *
 * <p>Vanilla routes every one of them through {@code offset(EnumFacing, int)}, which multiplies the
 * direction's three offsets by the distance — so {@code pos.up()} costs three multiplications and
 * three calls into {@link net.minecraft.util.EnumFacing} to add one to a coordinate.
 *
 * <p>The {@code n == 0} short-circuit is kept deliberately. It looks redundant next to a constructor
 * call, but for a {@code MutableBlockPos} vanilla returns {@code this} in that case rather than a
 * copy, and callers do rely on the identity — dropping it would silently hand out an immutable
 * snapshot where a live view was expected.
 */
@Mixin(BlockPos.class)
public abstract class BlockPosMixin extends Vec3i {
    public BlockPosMixin(int x, int y, int z) {
        super(x, y, z);
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos up() {
        return new BlockPos(this.getX(), this.getY() + 1, this.getZ());
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos up(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX(), this.getY() + n, this.getZ());
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos down() {
        return new BlockPos(this.getX(), this.getY() - 1, this.getZ());
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos down(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX(), this.getY() - n, this.getZ());
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos north() {
        return new BlockPos(this.getX(), this.getY(), this.getZ() - 1);
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos north(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX(), this.getY(), this.getZ() - n);
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos south() {
        return new BlockPos(this.getX(), this.getY(), this.getZ() + 1);
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos south(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX(), this.getY(), this.getZ() + n);
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos west() {
        return new BlockPos(this.getX() - 1, this.getY(), this.getZ());
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos west(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX() - n, this.getY(), this.getZ());
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos east() {
        return new BlockPos(this.getX() + 1, this.getY(), this.getZ());
    }

    /**
     * @author JellySquid
     * @reason Simplify and inline
     */
    @Overwrite
    public BlockPos east(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX() + n, this.getY(), this.getZ());
    }
}
