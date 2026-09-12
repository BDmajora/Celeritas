package com.bdmajora.coarctatio.state;

import net.minecraft.block.Block;

// Implemented on BlockStateContainer so the ExtendedBlockState mixin can reach the mapper; Block is a parameter because both callers run inside createState before the field is assigned
public interface MappedStateOwner {
    // Built on first call and cached; null when the block stays on vanilla states, so a decline costs one check
    PropertyValueMapper coarctatio$mapper(Block block);
}
