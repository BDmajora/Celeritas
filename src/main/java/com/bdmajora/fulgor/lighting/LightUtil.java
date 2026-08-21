package com.bdmajora.fulgor.lighting;

import com.bdmajora.fulgor.Fulgor;
import com.bdmajora.fulgor.api.LightInfoBlock;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * The four questions the lighting engine asks about a position, answered as cheaply as each one can be.
 *
 * <p>These sit on the innermost loop — six neighbours per position, and a bulk edit schedules tens of
 * thousands of positions — so each is worth a paragraph.
 */
public final class LightUtil {
    private static final IBlockState AIR = Blocks.AIR.getDefaultState();

    private LightUtil() {
    }

    /**
     * The block state at a position, read straight out of the section.
     *
     * <p>{@code Chunk.getBlockState} re-derives the section index, bounds-checks the position and
     * catches its own exceptions to build a crash report. None of that is useful here: the engine only
     * ever asks about positions it has already decoded from an encoded key, inside a chunk it has
     * already resolved.
     */
    public static IBlockState posToState(BlockPos pos, Chunk chunk) {
        return posToState(pos, chunk.getBlockStorageArray()[pos.getY() >> 4]);
    }

    public static IBlockState posToState(BlockPos pos, ExtendedBlockStorage section) {
        if (section == Chunk.NULL_BLOCK_STORAGE) {
            return AIR;
        }

        return section.getData().get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    /**
     * How much light this position emits.
     *
     * <p>The fast path skips the position-aware overload when {@link LightInfoBlock} has established
     * that the block does not override it — Forge's default for that overload only forwards back to
     * the state, through two more virtual calls on {@code Block} that a modpack makes megamorphic.
     */
    public static int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos, Chunk chunk) {
        if (Fulgor.hasDynamicLights() && DynamicLightsBridge.isAvailable()) {
            return DynamicLightsBridge.getLightValue(state, world, pos);
        }

        int value = Fulgor.useCachedBlockLightInfo()
                && !((LightInfoBlock) state.getBlock()).fulgor$hasPositionAwareLightValue()
                ? state.getLightValue()
                : state.getLightValue(world, pos);

        if (Fulgor.hasFluidloggedApi()) {
            return FluidLightCompat.getLightValue(value, world, pos, chunk);
        }

        return value;
    }

    /**
     * How much light this position removes from anything passing through it.
     *
     * <p>Same trade as {@link #getLightValue}. Called more often than it, too: luminance is asked once
     * per position, opacity once per position and again for each of its six neighbours.
     */
    public static int getLightOpacity(IBlockState state, IBlockAccess world, BlockPos pos, Chunk chunk) {
        if (Fulgor.hasFluidloggedApi()) {
            return FluidLightCompat.getLightOpacity(state, world, pos, chunk);
        }

        return Fulgor.useCachedBlockLightInfo()
                && !((LightInfoBlock) state.getBlock()).fulgor$hasPositionAwareOpacity()
                ? state.getLightOpacity()
                : state.getLightOpacity(world, pos);
    }
}
