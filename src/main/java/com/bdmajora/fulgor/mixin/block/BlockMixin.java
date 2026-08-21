package com.bdmajora.fulgor.mixin.block;

import com.bdmajora.fulgor.Fulgor;
import com.bdmajora.fulgor.api.LightInfoBlock;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Records, once per block, whether its light values can depend on where it is.
 *
 * <p>Forge gives {@code Block} position-aware {@code getLightValue} and {@code getLightOpacity}
 * overloads so that blocks whose light varies with their surroundings — a fluid's level, a lamp's
 * powered state read from a tile entity — can say so. The overwhelming majority of blocks override
 * neither, and Forge's defaults simply forward back to the state.
 *
 * <p>That forwarding is not free. {@code state.getLightValue(world, pos)} dispatches to the state
 * implementation, which dispatches to the block, which calls {@code state.getLightValue()}, which
 * dispatches to the block again. Both dispatches are on {@code Block}, and in a large modpack that
 * call site sees hundreds of distinct implementations, so it never inlines. Knowing the block did not
 * override the overload lets the engine start at {@code state.getLightValue()} and pay half of it.
 *
 * <p>Halving one call is not much on its own; it is worth having because the engine makes it for six
 * neighbours of every position in every batch.
 *
 * <h2>Why reflection, and why lazily</h2>
 *
 * <p>The question is "did any class between this one and {@code Block} redeclare the method", which is
 * exactly what {@code getDeclaringClass} on the resolved method answers and nothing else does. Doing it
 * lazily rather than in the constructor matters because blocks are constructed during mod loading,
 * while ASM-based coremods may still be rewriting their classes; the first light query happens once a
 * world exists, long after that has settled.
 *
 * <p>Both method names are Forge additions and so are not obfuscated, which is what makes looking them
 * up by name work identically in development and in production.
 */
@Mixin(Block.class)
public abstract class BlockMixin implements LightInfoBlock {
    @Unique
    private static final int FULGOR_RESOLVED = 1;
    @Unique
    private static final int FULGOR_POSITION_AWARE_LIGHT_VALUE = 1 << 1;
    @Unique
    private static final int FULGOR_POSITION_AWARE_OPACITY = 1 << 2;

    @Unique
    private byte fulgor$lightInfoFlags;

    @Override
    public boolean fulgor$hasPositionAwareLightValue() {
        return (fulgor$lightInfoFlags() & FULGOR_POSITION_AWARE_LIGHT_VALUE) != 0;
    }

    @Override
    public boolean fulgor$hasPositionAwareOpacity() {
        return (fulgor$lightInfoFlags() & FULGOR_POSITION_AWARE_OPACITY) != 0;
    }

    @Unique
    private int fulgor$lightInfoFlags() {
        int flags = this.fulgor$lightInfoFlags;

        if ((flags & FULGOR_RESOLVED) == 0) {
            flags = FULGOR_RESOLVED;

            if (fulgor$overrides("getLightValue")) {
                flags |= FULGOR_POSITION_AWARE_LIGHT_VALUE;
            }

            if (fulgor$overrides("getLightOpacity")) {
                flags |= FULGOR_POSITION_AWARE_OPACITY;
            }

            this.fulgor$lightInfoFlags = (byte) flags;
        }

        return flags;
    }

    /**
     * Whether something below {@code Block} declares the position-aware overload of {@code name}.
     *
     * <p>Fails safe: if the method cannot be resolved at all — a transformer removed it, a security
     * manager refused — the block is treated as position-aware, which is the slower answer but never
     * the wrong one.
     */
    @Unique
    private boolean fulgor$overrides(String name) {
        try {
            return getClass()
                    .getMethod(name, IBlockState.class, IBlockAccess.class, BlockPos.class)
                    .getDeclaringClass() != Block.class;
        } catch (NoSuchMethodException | SecurityException | LinkageError e) {
            Fulgor.LOGGER.warn("Could not determine whether {} overrides {}; assuming it does",
                    getClass().getName(), name, e);
            return true;
        }
    }
}
