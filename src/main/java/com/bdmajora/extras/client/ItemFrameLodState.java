package com.bdmajora.extras.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.EnumFacing;

import java.util.Collections;
import java.util.List;

/**
 * Render-thread state for item-frame level of detail, a backport of MoreCulling's "Frame LOD".
 *
 * <p>{@link #active} is set only for the duration of a single distant frame's content render, and
 * the item mixins consult {@link #filterLodQuads} while it is. Wall of framed blocks in a storage
 * room is the case this exists for: each one submits a full block model, and four of its six faces
 * are never visible through the frame.
 *
 * <p>Client render thread only, so a plain static flag needs no synchronization.
 */
public final class ItemFrameLodState {
    /** True only while rendering the contents of a framed item beyond the LOD distance. */
    public static boolean active;

    private ItemFrameLodState() {
    }

    /**
     * The model's quads for a face, minus the four side faces when LOD is active on a 3D model.
     *
     * <p>NORTH/SOUTH is the front/back axis for a framed item: vanilla block models use a
     * {@code fixed} display transform with zero rotation, so the viewer-facing face of a framed
     * block is its model-local SOUTH and the away face its NORTH. Flat sprite items have no sides
     * to drop and pass through unchanged, as does the general (null) bucket.
     */
    public static List<BakedQuad> filterLodQuads(IBakedModel model, IBlockState state, EnumFacing side, long rand) {
        List<BakedQuad> quads = model.getQuads(state, side, rand);

        if (!active || side == null || !model.isGui3d()) {
            return quads;
        }

        if (side == EnumFacing.NORTH || side == EnumFacing.SOUTH) {
            return quads;
        }

        return Collections.emptyList();
    }
}
