package com.bdmajora.impetus.impl.render.terrain;

import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import com.bdmajora.fulgor.FulgorRenderBridge;
import com.bdmajora.impetus.engine.impl.gl.device.CommandList;
import com.bdmajora.impetus.engine.impl.gl.device.RenderDevice;
import com.bdmajora.impetus.engine.impl.render.chunk.*;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.ChunkBuildOutput;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import com.bdmajora.impetus.engine.impl.render.chunk.data.BuiltRenderSectionData;
import com.bdmajora.impetus.engine.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.SectionTicker;
import com.bdmajora.impetus.engine.impl.render.chunk.occlusion.AsyncOcclusionMode;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderInterface;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderTextureSlot;
import com.bdmajora.impetus.engine.impl.render.chunk.sprite.GenericSectionSpriteTicker;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexType;
import com.bdmajora.impetus.engine.impl.render.viewport.Viewport;
import com.bdmajora.impetus.engine.impl.util.position.SectionPos;
import org.jetbrains.annotations.Nullable;
import com.bdmajora.impetus.ImpetusVintage;
import com.bdmajora.impetus.impl.render.terrain.compile.VintageChunkBuildContext;
import com.bdmajora.impetus.impl.render.terrain.compile.task.ChunkBuilderMeshingTask;
import com.bdmajora.impetus.impl.render.terrain.sprite.SpriteUtil;
import com.bdmajora.impetus.impl.world.WorldSlice;
import com.bdmajora.impetus.impl.world.cloned.ChunkRenderContext;
import com.bdmajora.impetus.impl.world.cloned.ClonedChunkSectionCache;

import java.util.List;

public class VintageRenderSectionManager extends RenderSectionManager {
    private final WorldClient world;
    @Getter
    private final ClonedChunkSectionCache sectionCache;

    public VintageRenderSectionManager(RenderPassConfiguration<?> configuration, WorldClient world, int renderDistance, CommandList commandList, int minSection, int maxSection) {
        super(configuration, () -> new VintageChunkBuildContext(world, configuration), ChunkRenderer::new, renderDistance, commandList, minSection, maxSection, ImpetusVintage.options().performance.chunkBuilderThreads, true);
        this.world = world;
        this.sectionCache = new ClonedChunkSectionCache(world);
    }

    /**
     * The Iris shadow pass re-drives this render path from the sun's point of view; routing it onto the dedicated
     * shadow render lists (instead of the main camera's culled lists) is what lets the shadow pass draw every
     * section in range regardless of player-view frustum/occlusion culling — the stability requirement behind
     * Complementary's {@code shadow.culling = reversed} directive.
     */
    @Override
    public boolean isInShadowPass() {
        return com.bdmajora.impetus.iris.pipeline.IrisShadowRenderer.isShadowPass();
    }

    public static VintageRenderSectionManager create(ChunkVertexType vertexType, WorldClient world, int renderDistance, CommandList commandList) {
        return new VintageRenderSectionManager(VintageRenderPassConfigurationBuilder.build(vertexType), world, renderDistance, commandList, 0, 16);
    }

    @Override
    protected AsyncOcclusionMode getAsyncOcclusionMode() {
        return ImpetusVintage.options().performance.asyncOcclusionMode;
    }

    @Override
    protected boolean shouldRespectUpdateTaskQueueSizeLimit() {
        return true;
    }

    @Override
    protected boolean useFogOcclusion() {
        return ImpetusVintage.options().performance.useFogOcclusion;
    }

    @Override
    protected boolean shouldUseOcclusionCulling(Viewport positionedViewport, boolean spectator) {
        if (isInShadowPass()) {
            // Voxelization must see a FRAME-STABLE section set: occlusion culling (especially async) lets cave
            // interiors and other marginal sections blink in and out of the shadow draw, which makes the pack's
            // colored-lighting floodfill chase a different voxel field every frame (permanent strobing).
            return false;
        }
        // `occlusion.culling = false`: the pack needs geometry the player cannot see (it is sampling the gbuffer
        // from another angle, e.g. for reflections or its own shadow logic).
        com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline pipeline =
                com.bdmajora.impetus.iris.Iris.getRenderingPipeline();
        if (pipeline != null && pipeline.shouldDisableOcclusionCulling()) {
            return false;
        }
        final boolean useOcclusionCulling;
        var camBlockPos = positionedViewport.getBlockCoord();
        BlockPos origin = new BlockPos(camBlockPos.x(), camBlockPos.y(), camBlockPos.z());

        if (spectator && this.world.getBlockState(origin).isOpaqueCube())
        {
            useOcclusionCulling = false;
        } else {
            useOcclusionCulling = Minecraft.getMinecraft().renderChunksMany;
        }

        return useOcclusionCulling;
    }

    @Override
    protected boolean isSectionVisuallyEmpty(int x, int y, int z) {
        Chunk chunk = this.world.getChunk(x, z);
        if (chunk.isEmpty()) {
            return true;
        }
        var array = chunk.getBlockStorageArray();
        if (y < 0 || y >= array.length) {
            return true;
        }
        // Deliberately not isEmpty(): Fulgor widens that to mean "has nothing worth sending to the
        // client", which is true of a blockless section only when its light is trivial too. The
        // visibility graph wants the narrower question of whether there is any geometry.
        return array[y] == Chunk.NULL_BLOCK_STORAGE || FulgorRenderBridge.isEmptyOfBlocks(array[y]);
    }

    @Override
    protected @Nullable ChunkBuilderTask<ChunkBuildOutput> createRebuildTask(RenderSection render, int frame) {
        ChunkRenderContext context = WorldSlice.prepare(this.world, new SectionPos(render.getChunkX(), render.getChunkY(), render.getChunkZ()), this.sectionCache);

        if (context == null) {
            return null;
        }

        return new ChunkBuilderMeshingTask(render, context, frame, this.cameraPosition);
    }

    @Override
    protected boolean allowImportantRebuilds() {
        return !ImpetusVintage.options().performance.alwaysDeferChunkUpdates;
    }

    @Override
    protected void scheduleSectionForRebuild(int x, int y, int z, boolean important) {
        this.sectionCache.invalidate(x, y, z);
        super.scheduleSectionForRebuild(x, y, z, important);
    }

    @Override
    public void updateChunks(boolean updateImmediately) {
        this.sectionCache.cleanup();
        super.updateChunks(updateImmediately);
    }

    /**
     * This ridiculous workaround is needed because some mods rely on side effects of calling getRenderBoundingBox
     * to initialize tile entity state. It can be removed if we start using getRenderBoundingBox for TE rendering
     * again.
     */
    @SuppressWarnings("unchecked")
    private static void retrieveBBForList(List<?> blockEntities) {
        if (!blockEntities.isEmpty()) {
            for (var be : (List<TileEntity>)blockEntities) {
                be.getRenderBoundingBox();
            }
        }
    }

    @Override
    protected boolean updateSectionInfo(RenderSection render, @Nullable BuiltRenderSectionData info) {
        if (info instanceof MinecraftBuiltRenderSectionData<?,?> mcData) {
            retrieveBBForList(mcData.culledBlockEntities);
            retrieveBBForList(mcData.globalBlockEntities);
        }
        return super.updateSectionInfo(render, info);
    }

    @Override
    protected @Nullable SectionTicker createSectionTicker() {
        return new GenericSectionSpriteTicker<>(SpriteUtil::markSpriteActive);
    }

    private static class ChunkRenderer extends DefaultChunkRenderer {

        public ChunkRenderer(RenderDevice device, RenderPassConfiguration<?> renderPassConfiguration) {
            super(device, renderPassConfiguration);
        }

        /**
         * Never in the shadow pass — Iris disables this the same way
         * ({@code MixinDefaultChunkRenderer#iris$disableBlockFaceCullingInShadowPass}).
         * <p>
         * {@code getVisibleFaces} drops each section's quads by facing relative to the OCCLUSION camera, which is
         * always the player's. That is correct for the gbuffer pass and completely wrong for the shadow pass, which
         * draws the same geometry from the sun. Standing 20 blocks underground, every section above the player fails
         * {@code originY > boundsMinY - 3}, so {@code MODEL_POS_Y} is cleared and every upward-facing block top in
         * those sections is dropped — precisely the surfaces the sun hits. The ground overhead then never reaches the
         * shadow map, those texels keep the 1.0 depth clear, and Complementary's light-shaft march reads
         * {@code clamp((1.0 - shadowPos.z) * 65536.0, 0.0, 1.0) == 1.0} — fully lit — for samples sitting under solid
         * rock. That is the sunlight leaking through the ground into caves.
         */
        @Override
        public boolean useBlockFaceCulling(){
            if (com.bdmajora.impetus.iris.pipeline.IrisShadowRenderer.isShadowPass()) {
                return false;
            }
            return ImpetusVintage.options().performance.useBlockFaceCulling;
        }

        @Override
        protected void configureShaderInterface(ChunkShaderInterface shader) {
            shader.setTextureSlot(ChunkShaderTextureSlot.BLOCK, 0);
            shader.setTextureSlot(ChunkShaderTextureSlot.LIGHT, 1);
        }
    }
}
