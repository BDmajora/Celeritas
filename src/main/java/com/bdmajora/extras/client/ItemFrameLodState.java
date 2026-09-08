package com.bdmajora.extras.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.EnumFacing;

import java.util.Collections;
import java.util.List;

// Render-thread state for item-frame level of detail, a backport of MoreCulling's "Frame LOD"
// Exists for walls of framed blocks in storage rooms: each submits a full block model, and four
// of its six faces are never visible through the frame
// Client render thread only, so a plain static flag needs no synchronization
public final class ItemFrameLodState {
    // True only while rendering the contents of a framed item beyond the LOD distance
    public static boolean active;

    private ItemFrameLodState() {
    }

    // The model's quads for a face, minus the four side faces when LOD is active on a 3D model
    // NORTH/SOUTH is the front/back axis: vanilla's fixed display transform has zero rotation, so
    // viewer-facing is model-local SOUTH and away is NORTH; flat sprites have no sides to drop
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
