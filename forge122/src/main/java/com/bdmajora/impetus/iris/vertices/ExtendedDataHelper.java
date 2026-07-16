package com.bdmajora.impetus.iris.vertices;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Derives the OptiFine extended per-vertex data from Minecraft state, ready to be written into
 * {@link com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexEncoder.Vertex}. All Minecraft accessors
 * here were checked against the build's deobfuscated 1.12.2 sources (MCP {@code stable_39}).
 * <p>
 * The meshing pipeline calls these when Iris is active (Task #6 wiring): {@code mc_Entity} = (block id, block meta),
 * {@code mc_midTexCoord} = the sprite center UV — which must come from the sprite, not an average of the quad's UVs,
 * or animated textures break.
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

    /** {@code mc_midTexCoord.x} — U of the sprite center. */
    public static float getMidTexU(TextureAtlasSprite sprite) {
        return (sprite.getMinU() + sprite.getMaxU()) * 0.5f;
    }

    /** {@code mc_midTexCoord.y} — V of the sprite center. */
    public static float getMidTexV(TextureAtlasSprite sprite) {
        return (sprite.getMinV() + sprite.getMaxV()) * 0.5f;
    }
}
