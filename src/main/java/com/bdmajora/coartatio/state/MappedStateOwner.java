package com.bdmajora.coartatio.state;

import net.minecraft.block.Block;

/**
 * Implemented on {@code BlockStateContainer} by a mixin, so that the container's lazily built
 * {@link PropertyValueMapper} can be reached from the {@code ExtendedBlockState} mixin — a separate
 * class, which cannot see the other mixin's unique field.
 *
 * <p>The block is a parameter rather than read from the container because both call sites are inside
 * {@code createState}, which runs during the container's constructor: the block field is assigned by
 * then, but passing the argument through avoids depending on that.
 */
public interface MappedStateOwner {
    /**
     * The mapper for this container, built on first call, or {@code null} if this block is staying on
     * vanilla states. The result is cached either way, so a declined block is only evaluated once.
     */
    PropertyValueMapper coartatio$mapper(Block block);
}
