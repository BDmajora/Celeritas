package com.bdmajora.coartatio.state;

import net.minecraft.block.Block;

// Implemented on BlockStateContainer by a mixin, so the container's lazily built PropertyValueMapper
// can be reached from the separate ExtendedBlockState mixin, which can't see this mixin's field directly.
// Block is passed as a parameter rather than read from the container because both call sites run inside
// createState (during the container's constructor), before the block field is guaranteed assigned.
public interface MappedStateOwner {
    // Mapper for this container, built on first call; null if the block stays on vanilla states.
    // Cached either way, so a declined block is only evaluated once.
    PropertyValueMapper coartatio$mapper(Block block);
}
