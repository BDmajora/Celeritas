package com.bdmajora.impetus.engine.impl.render.terrain;

import lombok.Getter;
import com.bdmajora.impetus.engine.impl.common.util.NativeBuffer;
import com.bdmajora.impetus.engine.impl.gl.device.CommandList;
import com.bdmajora.impetus.engine.impl.gl.device.RenderDevice;
import com.bdmajora.impetus.engine.impl.render.chunk.ChunkRenderMatrices;
import com.bdmajora.impetus.engine.impl.render.chunk.RenderPassConfiguration;
import com.bdmajora.impetus.engine.impl.render.chunk.RenderSectionManager;
import com.bdmajora.impetus.engine.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.ChunkRenderList;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.SortedRenderLists;
import com.bdmajora.impetus.engine.impl.render.chunk.map.ChunkTracker;
import com.bdmajora.impetus.engine.impl.render.chunk.map.ChunkTrackerHolder;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;
import com.bdmajora.impetus.engine.impl.render.viewport.CameraTransform;
import com.bdmajora.impetus.engine.impl.render.viewport.Viewport;
import com.bdmajora.impetus.engine.impl.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * Provides an extension to a game's regular world renderer.
 */
public abstract class SimpleWorldRenderer<WORLD, SECTIONMANAGER extends RenderSectionManager, LAYER, BLOCKENTITY, BLOCKENTITY_RENDER_CONTEXT> {
    protected WORLD world;
    protected int renderDistance;

    public record CameraState(double x, double y, double z, double pitch, double yaw, float fogDistance) {}

    protected CameraState lastCameraState;

    protected Viewport currentViewport;

    @Getter
    protected SECTIONMANAGER renderSectionManager;

    public void setWorld(WORLD world) {
        // Check that the world is actually changing
        if (this.world == world) {
            return;
        }

        // If we have a world is already loaded, unload the renderer
        if (this.world != null) {
            this.unloadWorld();
        }

        // If we're loading a new world, load the renderer
        if (world != null) {
            this.loadWorld(world);
        }
    }

    protected void loadWorld(WORLD world) {
        this.world = world;

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.initRenderer(commandList);
        }
    }

    protected void unloadWorld() {
        if (this.renderSectionManager != null) {
            this.renderSectionManager.destroy();
            this.renderSectionManager = null;
        }

        this.world = null;
    }

    /**
     * @return The number of chunk renders which are visible in the current camera's frustum
     */
    public int getVisibleChunkCount() {
        return this.renderSectionManager.getVisibleChunkCount();
    }

    /**
     * Notifies the chunk renderer that the graph scene has changed and should be re-computed.
     */
    public void scheduleTerrainUpdate() {
        // BUG: seems to be called before init
        if (this.renderSectionManager != null) {
            this.renderSectionManager.markGraphDirty();
        }
    }

    /**
     * @return True if no chunks are pending rebuilds
     */
    public boolean isTerrainRenderComplete() {
        return this.renderSectionManager.getBuilder().isBuildQueueEmpty();
    }

    public abstract int getEffectiveRenderDistance();

    /**
     * Called prior to any chunk rendering in order to update necessary state.
     */
    public void setupTerrain(Viewport viewport,
                             CameraState cameraState,
                             @Deprecated(forRemoval = true) int frame,
                             boolean spectator,
                             boolean updateChunksImmediately) {
        NativeBuffer.reclaim(false);

        if (this.renderSectionManager != null) {
            this.renderSectionManager.finishAllGraphUpdates();
        }

        if (this.renderSectionManager.isInShadowPass()) {
            // Umbra parity. The shadow pass is a pure culling pass: it builds its own render list from the shadow
            // frustum and touches nothing the camera pass owns. Umbra enforces this structurally by swapping
            // `visibleSections` and the `prevCamRotX/prevCamRotY` camera memo out for the duration of the pass
            // (ShadowRenderer#renderShadows -> CullingDataCache#saveState/restoreState) and by never running
            // vanilla's chunk build/upload dispatch from it — it calls only `invokeCullTerrain`.
            //
            // Every piece of shared state below caused a real bug when the shadow pass reached it:
            //
            //  - `lastCameraState`. Both passes are handed the SAME CameraState, and the shadow pass runs first
            //    (the EntityRenderer "frustum" hook fires before RenderGlobal.setupTerrain), so the shadow pass
            //    always won the dirty check and the camera pass always saw "camera unchanged". The camera pass
            //    therefore never marked its own graph dirty and depended entirely on the shadow pass having done
            //    it — the exact coupling Umbra's memo swap exists to prevent.
            //  - `updateChunks`. The rebuild lists it drains belong to whichever manager is current, so the shadow
            //    pass dispatched builds off the SHADOW list while spending the shared ChunkBuilder scheduling
            //    budget. The camera pass then ran with what was left, so on a streaming world terrain fell further
            //    behind every frame and never caught up: geometry drains away while entities and block entities,
            //    which do not come from these lists, keep drawing.
            //  - `tickVisibleRenders`. Ticks the current render list, so running it in both passes double-ticked
            //    animated sprites.
            //
            // `currentViewport` is deliberately still assigned: drawChunkLayer reads it for the occlusion camera,
            // and the camera pass reassigns it before its own draws. Block face culling is off in this pass
            // (VintageRenderSectionManager#useBlockFaceCulling), so it only supplies the camera transform, which
            // is identical in both viewports.
            this.currentViewport = viewport;
            this.renderSectionManager.update(viewport, frame, spectator);
            return;
        }

        this.processChunkEvents();

        this.renderSectionManager.runAsyncTasks();

        if (getEffectiveRenderDistance() != this.renderDistance) {
            this.reload();
        }

        boolean dirty = this.lastCameraState == null || !this.lastCameraState.equals(cameraState);

        if (dirty) {
            this.renderSectionManager.markGraphDirty();
            this.lastCameraState = cameraState;
        }

        this.currentViewport = viewport;

        this.renderSectionManager.runAsyncTasks();

        this.renderSectionManager.updateChunks(updateChunksImmediately);

        this.renderSectionManager.uploadChunks();

        if (this.renderSectionManager.needsUpdate()) {
            this.renderSectionManager.update(viewport, frame, spectator);
        }

        if (updateChunksImmediately) {
            this.renderSectionManager.uploadChunks();
        }

        this.renderSectionManager.tickVisibleRenders();
    }

    private void processChunkEvents() {
        var tracker = ChunkTrackerHolder.get(this.world);
        tracker.forEachEvent(this.renderSectionManager::onChunkAdded, this.renderSectionManager::onChunkRemoved);
    }

    protected abstract ChunkRenderMatrices createChunkRenderMatrices();

    public Viewport getLastViewport() {
        return this.currentViewport;
    }

    /**
     * Performs a render pass for the given {@link LAYER} and draws all visible chunks for it.
     */
    public void drawChunkLayer(LAYER renderLayer, double x, double y, double z) {
        ChunkRenderMatrices matrices = createChunkRenderMatrices();

        Collection<TerrainRenderPass> passes = this.renderSectionManager.getRenderPassConfiguration().vanillaRenderStages().get(renderLayer);

        if (passes != null && !passes.isEmpty()) {
            var occlusionCamera = this.getLastViewport().getTransform();
            var realCamera = new CameraTransform(x, y, z);
            for (var pass : passes) {
                this.renderSectionManager.renderLayer(matrices, pass, occlusionCamera, realCamera);
            }
        }
    }

    public void reload() {
        if (this.world == null) {
            return;
        }

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.initRenderer(commandList);
        }
    }

    protected abstract SECTIONMANAGER createRenderSectionManager(CommandList commandList);

    protected void initRenderer(CommandList commandList) {
        if (this.renderSectionManager != null) {
            this.renderSectionManager.destroy();
            this.renderSectionManager = null;
        }

        this.renderDistance = getEffectiveRenderDistance();

        this.renderSectionManager = this.createRenderSectionManager(commandList);

        var tracker = ChunkTrackerHolder.get(this.world);
        ChunkTracker.forEachChunk(tracker.getReadyChunks(), this.renderSectionManager::onChunkAdded);
    }

    /**
     * {@return an iterator over all visible block entities}
     */
    public Iterator<BLOCKENTITY> blockEntityIterator() {
        return MinecraftBuiltRenderSectionData.generateBlockEntityIterator(this.renderSectionManager.getRenderLists(), this.renderSectionManager.getSectionsWithGlobalEntities());
    }

    public void forEachVisibleBlockEntity(Consumer<BLOCKENTITY> consumer) {
        MinecraftBuiltRenderSectionData.forEachBlockEntity(consumer, this.renderSectionManager.getRenderLists(), this.renderSectionManager.getSectionsWithGlobalEntities());
    }

    protected abstract void renderBlockEntityList(List<BLOCKENTITY> list, BLOCKENTITY_RENDER_CONTEXT context);

    private int renderCulledBlockEntities(BLOCKENTITY_RENDER_CONTEXT renderContext) {
        int count = 0;
        SortedRenderLists renderLists = this.renderSectionManager.getRenderLists();
        Iterator<ChunkRenderList> renderListIterator = renderLists.iterator();

        while (renderListIterator.hasNext()) {
            var renderList = renderListIterator.next();

            var renderRegion = renderList.getRegion();
            var renderSectionIterator = renderList.sectionsWithEntitiesIterator();

            if (renderSectionIterator == null) {
                continue;
            }

            while (renderSectionIterator.hasNext()) {
                var renderSectionId = renderSectionIterator.nextByteAsInt();
                var renderSection = renderRegion.getSection(renderSectionId);

                if (renderSection == null) {
                    continue;
                }

                var context = renderSection.getBuiltContext();

                if (!(context instanceof MinecraftBuiltRenderSectionData mcData)) {
                    continue;
                }

                List<BLOCKENTITY> blockEntities = mcData.culledBlockEntities;

                if (blockEntities.isEmpty()) {
                    continue;
                }

                count += blockEntities.size();

                this.renderBlockEntityList(blockEntities, renderContext);
            }
        }

        return count;
    }

    private int renderGlobalBlockEntities(BLOCKENTITY_RENDER_CONTEXT renderContext) {
        int count = 0;
        for (var renderSection : this.renderSectionManager.getSectionsWithGlobalEntities()) {
            var context = renderSection.getBuiltContext();

            if (!(context instanceof MinecraftBuiltRenderSectionData mcData)) {
                continue;
            }

            List<BLOCKENTITY> blockEntities = mcData.globalBlockEntities;

            if (blockEntities.isEmpty()) {
                continue;
            }

            count += blockEntities.size();

            this.renderBlockEntityList(blockEntities, renderContext);
        }

        return count;
    }

    public int renderBlockEntities(BLOCKENTITY_RENDER_CONTEXT renderContext) {
        int count = 0;
        count += this.renderCulledBlockEntities(renderContext);
        count += this.renderGlobalBlockEntities(renderContext);
        return count;
    }

    // the volume of a section multiplied by the number of sections to be checked at most
    public static final double MAX_ENTITY_CHECK_VOLUME = 16 * 16 * 16 * 15;

    public abstract int getMinimumBuildHeight();
    public abstract int getMaximumBuildHeight();

    public boolean isPointVisible(double x, double y, double z) {
        if (y < getMinimumBuildHeight() + 0.5D || y > getMaximumBuildHeight() - 0.5D) {
            return true;
        }

        return this.renderSectionManager.isSectionVisible(
                PositionUtil.posToSectionCoord(x),
                PositionUtil.posToSectionCoord(y),
                PositionUtil.posToSectionCoord(z)
        );
    }

    public boolean isBoxVisible(double x1, double y1, double z1, double x2, double y2, double z2) {
        // Boxes outside the valid world height will never map to a rendered chunk
        // Always render these boxes or they'll be culled incorrectly!
        if (y2 < getMinimumBuildHeight() + 0.5D || y1 > getMaximumBuildHeight() - 0.5D) {
            return true;
        }

        double entityVolume = (x2 - x1) * (y2 - y1) * (z2 - z1);
        if (entityVolume > MAX_ENTITY_CHECK_VOLUME) {
            return true;
        }

        int minX = PositionUtil.posToSectionCoord(x1 - 0.5D);
        int minY = PositionUtil.posToSectionCoord(y1 - 0.5D);
        int minZ = PositionUtil.posToSectionCoord(z1 - 0.5D);

        int maxX = PositionUtil.posToSectionCoord(x2 + 0.5D);
        int maxY = PositionUtil.posToSectionCoord(y2 + 0.5D);
        int maxZ = PositionUtil.posToSectionCoord(z2 + 0.5D);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (this.renderSectionManager.isSectionVisible(x, y, z)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public String getChunksDebugString() {
        // C: visible/total D: distance
        return String.format("C: %d/%d D: %d %s", this.renderSectionManager.getVisibleChunkCount(),
                this.renderSectionManager.getTotalSections(), this.renderDistance,
                this.renderSectionManager.getTickerDebugString());
    }

    public RenderPassConfiguration<?> getRenderPassConfiguration() {
        return this.renderSectionManager.getRenderPassConfiguration();
    }

    /**
     * Schedules chunk rebuilds for all chunks in the specified block region.
     */
    public void scheduleRebuildForBlockArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean important) {
        this.scheduleRebuildForChunks(minX >> 4, minY >> 4, minZ >> 4, maxX >> 4, maxY >> 4, maxZ >> 4, important);
    }

    /**
     * Schedules chunk rebuilds for all chunks in the specified chunk region.
     */
    public void scheduleRebuildForChunks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean important) {
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkY = minY; chunkY <= maxY; chunkY++) {
                for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                    this.scheduleRebuildForChunk(chunkX, chunkY, chunkZ, important);
                }
            }
        }
    }

    /**
     * Schedules a chunk rebuild for the render belonging to the given chunk section position.
     */
    public void scheduleRebuildForChunk(int x, int y, int z, boolean important) {
        this.renderSectionManager.scheduleRebuild(x, y, z, important);
    }

    public Collection<String> getDebugStrings() {
        var debugStrings = new ArrayList<String>();
        if (this.currentViewport != null) {
            var transform = this.currentViewport.getTransform();
            debugStrings.add("Viewport: %.02f %.02f %.02f".formatted(transform.x, transform.y, transform.z));
        }
        if (this.renderSectionManager != null) {
            debugStrings.addAll(this.renderSectionManager.getDebugStrings());
        }
        return debugStrings;
    }

    public boolean isSectionReady(int x, int y, int z) {
        return this.renderSectionManager.isSectionBuilt(x, y, z);
    }

    /**
     * This interface should be implemented on the WorldRenderer or equivalent class.
     */
    public interface Provider<T extends SimpleWorldRenderer<?, ?, ?, ?, ?>> {
        T impetus$getWorldRenderer();

        @SuppressWarnings("unchecked")
        static <T extends SimpleWorldRenderer<?, ?, ?, ?, ?>> @Nullable T getWorldRendererNullable(Object o) {
            return ((Provider<T>)o).impetus$getWorldRenderer();
        }

        static <T extends SimpleWorldRenderer<?, ?, ?, ?, ?>> @NotNull T getWorldRenderer(Object o) {
            T result = getWorldRendererNullable(o);
            if (result == null) {
                throw new IllegalStateException("No renderer attached to active world");
            }
            return result;
        }
    }
}
