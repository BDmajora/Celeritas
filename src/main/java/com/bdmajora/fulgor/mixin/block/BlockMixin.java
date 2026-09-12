package com.bdmajora.fulgor.mixin.block;

import com.bdmajora.fulgor.Fulgor;
import com.bdmajora.fulgor.api.LightInfoBlock;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// Caches on Block whether it overrides Forge's position-aware getLightValue/getLightOpacity, skipping the state->block->state double dispatch for six neighbours per position; resolved lazily by reflection since coremods may still be rewriting classes at construction, and both names are unobfuscated Forge additions
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

    // Fails safe: if the method cannot be resolved (transformer removed it, security manager refused), treat the block as position-aware, slower but never wrong
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
