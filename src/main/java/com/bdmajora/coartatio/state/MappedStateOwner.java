package com.bdmajora.coartatio.state;

import net.minecraft.block.Block;

// Implemented on BlockStateContainer so the ExtendedBlockState mixin can reach the container's mapper
// Block is a parameter, not a field read, because both callers run inside createState before it is assigned
public interface MappedStateOwner {
    // Built on first call and cached; null when the block stays on vanilla states, so a decline costs one check
    PropertyValueMapper coartatio$mapper(Block block);
}
