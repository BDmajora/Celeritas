package com.bdmajora.equilibrium.mixin.block.redstone_wire;

import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

// removes the repeated block reads from redstone dust power propagation
// vanilla's calculateCurrentChanges reads the world more often than it needs to, in two ways that
// compound: each of the four horizontal neighbours has its state fetched twice, once for the
// isNormalCube test and once again for the negated form of the same test in the else-if
// and the block directly above the wire, which does not depend on the direction being examined, is
// fetched inside the loop - so up to four times per call, each time allocating a position to fetch it
// the rewrite reads each neighbour once and resolves the block above lazily, on first use, so a call
// that never reaches the test still does not pay for it
// that laziness is not premature: the common case for dust is a flat line where no horizontal
// neighbour is a solid cube, and hoisting the read unconditionally would add work to exactly that case
// everything else is preserved as written, including the ordering of the three getMaxCurrentStrength
// calls and the canProvidePower toggle around the neighbour power query - dust propagation is the
// most contraption-sensitive code in the game and the shape of this method is load-bearing, so only
// the redundant reads are gone
@Mixin(BlockRedstoneWire.class)
public abstract class BlockRedstoneWireMixin {
    @Shadow
    @Final
    private Set<BlockPos> blocksNeedingUpdate;

    @Shadow
    private boolean canProvidePower;

    // Private in the target, so it cannot be shadowed as abstract; the body is never reached.
    @Shadow
    private int getMaxCurrentStrength(World worldIn, BlockPos pos, int strength) {
        throw new AssertionError();
    }

    // Overwrite: vanilla's power calculation without the redundant neighbour set allocations
    @Overwrite
    private IBlockState calculateCurrentChanges(World worldIn, BlockPos pos1, BlockPos pos2, IBlockState state) {
        IBlockState previousState = state;

        int previousPower = state.getValue(BlockRedstoneWire.POWER);
        int power = this.getMaxCurrentStrength(worldIn, pos2, 0);

        this.canProvidePower = false;
        int neighbourPower = worldIn.getRedstonePowerFromNeighbors(pos1);
        this.canProvidePower = true;

        if (neighbourPower > 0 && neighbourPower > power - 1) {
            power = neighbourPower;
        }

        int wirePower = 0;

        // Tri-state: 0 not yet resolved, 1 solid, -1 not solid. Resolved at most once per call, and
        // only if some horizontal neighbour turns out to be a solid cube.
        int aboveIsNormalCube = 0;

        for (EnumFacing facing : EnumFacing.Plane.HORIZONTAL) {
            BlockPos neighbourPos = pos1.offset(facing);

            boolean isNotOrigin = neighbourPos.getX() != pos2.getX() || neighbourPos.getZ() != pos2.getZ();

            if (isNotOrigin) {
                wirePower = this.getMaxCurrentStrength(worldIn, neighbourPos, wirePower);
            }

            boolean neighbourIsNormalCube = worldIn.getBlockState(neighbourPos).isNormalCube();

            if (neighbourIsNormalCube) {
                if (aboveIsNormalCube == 0) {
                    aboveIsNormalCube = worldIn.getBlockState(pos1.up()).isNormalCube() ? 1 : -1;
                }

                if (aboveIsNormalCube == -1 && isNotOrigin && pos1.getY() >= pos2.getY()) {
                    wirePower = this.getMaxCurrentStrength(worldIn, neighbourPos.up(), wirePower);
                }
            } else if (isNotOrigin && pos1.getY() <= pos2.getY()) {
                wirePower = this.getMaxCurrentStrength(worldIn, neighbourPos.down(), wirePower);
            }
        }

        if (wirePower > power) {
            power = wirePower - 1;
        } else if (power > 0) {
            --power;
        } else {
            power = 0;
        }

        if (neighbourPower > power - 1) {
            power = neighbourPower;
        }

        if (previousPower != power) {
            state = state.withProperty(BlockRedstoneWire.POWER, power);

            // Only write if nothing else changed the block underneath us while the strengths above
            // were being gathered — vanilla's guard, kept verbatim.
            if (worldIn.getBlockState(pos1) == previousState) {
                worldIn.setBlockState(pos1, state, 2);
            }

            this.blocksNeedingUpdate.add(pos1);

            for (EnumFacing facing : EnumFacing.VALUES) {
                this.blocksNeedingUpdate.add(pos1.offset(facing));
            }
        }

        return state;
    }
}
