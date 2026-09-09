package com.bdmajora.impetus.impl.render.terrain;

import com.github.bsideup.jabel.Desugar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.client.MinecraftForgeClient;
import com.bdmajora.impetus.engine.impl.gl.device.CommandList;
import com.bdmajora.impetus.engine.impl.render.chunk.ChunkRenderMatrices;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderFogComponent;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkMeshFormats;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexType;
import com.bdmajora.impetus.engine.impl.render.terrain.SimpleWorldRenderer;
import com.bdmajora.impetus.ImpetusVintage;
import com.bdmajora.impetus.mixin.core.terrain.ActiveRenderInfoAccessor;

import java.util.*;

// extends vanilla's RenderGlobal with the Impetus terrain renderer's own draw and visibility entry points
public class ImpetusWorldRenderer extends SimpleWorldRenderer<WorldClient, VintageRenderSectionManager, BlockRenderLayer, TileEntity, ImpetusWorldRenderer.TileEntityRenderContext>  {
    @Desugar
    public record TileEntityRenderContext(Map<Integer, DestroyBlockProgress> damagedBlocks, float partialTicks) {}

    // the ImpetusWorldRenderer for the current dimension
    public static ImpetusWorldRenderer instance() {
        return SimpleWorldRenderer.Provider.getWorldRenderer(Minecraft.getMinecraft().renderGlobal);
    }

    // the ImpetusWorldRenderer for the current dimension, or null if none is attached
    public static ImpetusWorldRenderer instanceNullable() {
        return SimpleWorldRenderer.Provider.getWorldRendererNullable(Minecraft.getMinecraft().renderGlobal);
    }

    @Override
    public int getMinimumBuildHeight() {
        return 0;
    }

    @Override
    public int getMaximumBuildHeight() {
        return this.world.getHeight();
    }

    @Override
    public int getEffectiveRenderDistance() {
        return Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
    }

    @Override
    protected ChunkRenderMatrices createChunkRenderMatrices() {
        if (com.bdmajora.impetus.umbra.pipeline.UmbraShadowRenderer.isShadowPass()) {
            // The Umbra shadow pass re-drives this render path from the sun's point of view.
            var state = com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState.INSTANCE;
            return new ChunkRenderMatrices(state.getShadowProjection(), state.getShadowModelView());
        }
        return new ChunkRenderMatrices(ActiveRenderInfoAccessor.getProjectionMatrix(), ActiveRenderInfoAccessor.getModelViewMatrix());
    }

    @Override
    protected VintageRenderSectionManager createRenderSectionManager(CommandList commandList) {
        return VintageRenderSectionManager.create(chooseVertexType(), this.world, this.getEffectiveRenderDistance(), commandList);
    }

    // performs a render pass for the given BlockRenderLayer, drawing every visible chunk for it
    public void drawChunkLayer(BlockRenderLayer renderLayer, double x, double y, double z) {
        // Umbra renderStage uniform: packs gate voxelization on MC_RENDER_STAGE_TERRAIN_* (ordinals 8/9/10/17).
        int stage;
        switch (renderLayer) {
            case SOLID: stage = 8; break;
            case CUTOUT_MIPPED: stage = 9; break;
            case CUTOUT: stage = 10; break;
            case TRANSLUCENT: stage = 17; break;
            default: stage = 0; break;
        }
        com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState.INSTANCE.setRenderStage(stage);
        try {
            super.drawChunkLayer(renderLayer, x, y, z);
        } finally {
            com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState.INSTANCE.setRenderStage(0);
        }

        GlStateManager.resetColor();
    }

    public static CameraState captureCameraState(double ticks) {
        Entity viewEntity = Objects.requireNonNull(Minecraft.getMinecraft().getRenderViewEntity(), "Client must have view entity");

        double x = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * ticks;
        double y = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * ticks + (double) viewEntity.getEyeHeight();
        double z = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * ticks;

        float pitch = viewEntity.rotationPitch;
        float yaw = viewEntity.rotationYaw;
        float fogDistance = ChunkShaderFogComponent.FOG_SERVICE.getFogCutoff();

        return new CameraState(x, y, z, pitch, yaw, fogDistance);
    }


    @Override
    protected void renderBlockEntityList(List<TileEntity> list, TileEntityRenderContext tileEntityRenderContext) {
        int pass = MinecraftForgeClient.getRenderPass();
        float partialTicks = tileEntityRenderContext.partialTicks;

        for (TileEntity tileEntity : list) {
            if(!tileEntity.shouldRenderInPass(pass))
                continue;

            try {
                TileEntityRendererDispatcher.instance.render(tileEntity, partialTicks, -1);
            } catch(RuntimeException e) {
                if(tileEntity.isInvalid()) {
                    ImpetusVintage.logger().error("Suppressing crash from invalid tile entity", e);
                } else {
                    throw e;
                }
            }
        }
    }

    @Override
    public int renderBlockEntities(TileEntityRenderContext tileEntityRenderContext) {
        int pass = MinecraftForgeClient.getRenderPass();
        TileEntityRendererDispatcher.instance.preDrawBatch();
        int count = super.renderBlockEntities(tileEntityRenderContext);
        TileEntityRendererDispatcher.instance.drawBatch(pass);
        return count;
    }

    // whether the entity intersects any visible chunk in the graph
    public boolean isEntityVisible(Entity entity) {
        if (!ImpetusVintage.options().performance.useEntityCulling || this.renderSectionManager.isInShadowPass()) {
            return true;
        }

        // Ensure entities with outlines or nametags are always visible
        if (entity.isGlowing() || entity.getAlwaysRenderNameTagForRender()) {
            return true;
        }

        //? if <1.21.2
        AxisAlignedBB box = entity.getRenderBoundingBox();
        //? if >=1.21.2
        /*AABB box = renderer.getBoundingBoxForCulling(entity);*/

        return this.isBoxVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private ChunkVertexType chooseVertexType() {
        // When a shader pack is active, terrain is drawn by the pack's transformed gbuffers_terrain, which reads
        // the vanilla-like float layout plus the OptiFine extended attributes (true normals, at_tangent,
        // mc_midTexCoord, mc_Entity) that UmbraChunkVertexType appends.
        if (com.bdmajora.impetus.umbra.terrain.UmbraTerrainProgramOverride.areShadersActive()) {
            return com.bdmajora.impetus.umbra.vertices.UmbraChunkVertexType.INSTANCE;
        }

        if (!ImpetusVintage.options().performance.useCompactVertexFormat) {
            return ChunkMeshFormats.VANILLA_LIKE;
        }

        return ChunkMeshFormats.COMPACT;
    }
}
