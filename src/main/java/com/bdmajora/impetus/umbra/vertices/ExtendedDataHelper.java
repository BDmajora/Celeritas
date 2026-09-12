package com.bdmajora.impetus.umbra.vertices;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

// Derives OptiFine's extended per-vertex data from Minecraft state for the mesher, only while a pack is active
// No mc_midTexCoord helper on purpose: it is the quad's UV centre, not the sprite's, so it cannot come from a sprite
public final class ExtendedDataHelper {
    private ExtendedDataHelper() {
    }

    // mc_Entity.x — the block's raw registry id, used when the pack's block.properties does not map this state
    public static int getBlockId(IBlockState state) {
        return Block.getIdFromBlock(state.getBlock());
    }

    // mc_Entity.z — the 1.12.2 metadata for this state
    public static int getBlockData(IBlockState state) {
        return state.getBlock().getMetaFromState(state);
    }
}
