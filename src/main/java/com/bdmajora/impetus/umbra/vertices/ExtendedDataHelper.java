package com.bdmajora.impetus.umbra.vertices;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

// Derives the OptiFine extended per-vertex data from Minecraft state, ready to write into
// ChunkVertexEncoder.Vertex. Only called by the mesher while a shader pack is active
// Every Minecraft accessor here was checked against the build's deobfuscated 1.12.2 sources (MCP stable_39)
// There is deliberately NO mc_midTexCoord helper. That attribute is the centre of the texture region mapped to a
// QUAD — the mean of its four vertex UVs, as in Iris's XHFPTerrainVertex — so it cannot be derived from a
// TextureAtlasSprite at all: the sprite centre only equals it for quads that map the whole sprite
// An earlier sprite-based pair of accessors here is exactly what produced the torch-cap artifact. The live
// computation lives at the two meshing sites instead, in VintageBlockRenderer.populateUmbraVertexData
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
