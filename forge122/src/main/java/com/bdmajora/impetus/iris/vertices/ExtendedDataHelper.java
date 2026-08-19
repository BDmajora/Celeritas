package com.bdmajora.impetus.iris.vertices;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Derives the OptiFine extended per-vertex data from Minecraft state, ready to be written into
 * {@link com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexEncoder.Vertex}. All Minecraft accessors
 * here were checked against the build's deobfuscated 1.12.2 sources (MCP {@code stable_39}).
 * <p>
 * The meshing pipeline calls these when Iris is active (Task #6 wiring): {@code mc_Entity} = (block id, block meta).
 * <p>
 * There is deliberately no {@code mc_midTexCoord} helper here. That attribute is the centre of the texture region
 * mapped to a <em>quad</em> (the mean of its four vertex UVs, as in Iris's {@code XHFPTerrainVertex}), so it cannot
 * be derived from a {@link TextureAtlasSprite} at all — the sprite centre is only the same value for quads that map
 * the whole sprite. An earlier sprite-based pair of accessors here is what seeded the torch-cap artifact; the live
 * computation lives at the two meshing sites (see {@code VintageBlockRenderer.populateIrisVertexData}).
 */
public final class ExtendedDataHelper {
    private ExtendedDataHelper() {
    }

    /** {@code mc_Entity.x} — the block's registry id. */
    public static int getBlockId(IBlockState state) {
        return Block.getIdFromBlock(state.getBlock());
    }

    /** {@code mc_Entity.y} — the block's metadata for this state. */
    public static int getBlockData(IBlockState state) {
        return state.getBlock().getMetaFromState(state);
    }
}
