package com.bdmajora.equilibrium.mixin.math.fast_blockpos;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

// replaces the six directional offset helpers with direct construction
// vanilla routes every one of them through offset(EnumFacing, int), which multiplies the direction's
// three offsets by the distance - so pos.up() costs three multiplications and three calls into
// EnumFacing to add one to a coordinate
// the n == 0 short-circuit is kept deliberately: it looks redundant next to a constructor call, but
// for a MutableBlockPos vanilla returns this in that case rather than a copy, and callers do rely on
// the identity - dropping it would silently hand out an immutable snapshot where a live view was
// expected
@Mixin(BlockPos.class)
public abstract class BlockPosMixin extends Vec3i {
    public BlockPosMixin(int x, int y, int z) {
        super(x, y, z);
    }

    // Overwrite: direct constructor call instead of offset() through EnumFacing
    @Overwrite
    public BlockPos up() {
        return new BlockPos(this.getX(), this.getY() + 1, this.getZ());
    }

    // Overwrite: returns this for n == 0, matching vanilla
    @Overwrite
    public BlockPos up(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX(), this.getY() + n, this.getZ());
    }

    // Overwrite: direct constructor call
    @Overwrite
    public BlockPos down() {
        return new BlockPos(this.getX(), this.getY() - 1, this.getZ());
    }

    // Overwrite: returns this for n == 0
    @Overwrite
    public BlockPos down(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX(), this.getY() - n, this.getZ());
    }

    // Overwrite: direct constructor call
    @Overwrite
    public BlockPos north() {
        return new BlockPos(this.getX(), this.getY(), this.getZ() - 1);
    }

    // Overwrite: returns this for n == 0
    @Overwrite
    public BlockPos north(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX(), this.getY(), this.getZ() - n);
    }

    // Overwrite: direct constructor call
    @Overwrite
    public BlockPos south() {
        return new BlockPos(this.getX(), this.getY(), this.getZ() + 1);
    }

    // Overwrite: returns this for n == 0
    @Overwrite
    public BlockPos south(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX(), this.getY(), this.getZ() + n);
    }

    // Overwrite: direct constructor call
    @Overwrite
    public BlockPos west() {
        return new BlockPos(this.getX() - 1, this.getY(), this.getZ());
    }

    // Overwrite: returns this for n == 0
    @Overwrite
    public BlockPos west(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX() - n, this.getY(), this.getZ());
    }

    // Overwrite: direct constructor call
    @Overwrite
    public BlockPos east() {
        return new BlockPos(this.getX() + 1, this.getY(), this.getZ());
    }

    // Overwrite: returns this for n == 0
    @Overwrite
    public BlockPos east(int n) {
        return n == 0 ? (BlockPos) (Object) this : new BlockPos(this.getX() + n, this.getY(), this.getZ());
    }
}
