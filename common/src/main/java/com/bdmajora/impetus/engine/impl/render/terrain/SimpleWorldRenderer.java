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

// The version-independent half of the world renderer: everything about driving the section manager that does
// not need to name a Minecraft class
// Each supported game version subclasses this and fills in the type parameters with its own world, layer and
// block-entity types, so the per-frame ordering below is written once instead of once per version
public abstract class SimpleWorldRenderer<WORLD, SECTIONMANAGER extends RenderSectionManager, LAYER, BLOCKENTITY, BLOCKENTITY_RENDER_CONTEXT> {
    // Null until a world is loaded; used as the "is a world loaded" flag as well as the world itself
    protected WORLD world;
    // The distance the section manager was last built for, so a settings change can be noticed and force a reload
    protected int renderDistance;

    // Everything about the camera that, when changed, invalidates the visibility graph
    // A record so the dirty check below is a single equals() rather than six field comparisons
    // fogDistance is in here because fog distance clips the graph traversal, so changing it changes visibility
    public record CameraState(double x, double y, double z, double pitch, double yaw, float fogDistance) {}

    // The camera state the graph was last built for; null before the first frame
    protected CameraState lastCameraState;

    // The viewport of the pass currently being set up, read back by drawChunkLayer for its occlusion camera
    protected Viewport currentViewport;

    @Getter
    protected SECTIONMANAGER renderSectionManager;

    // Swaps the renderer over to a different world, tearing down and rebuilding the section manager
    // Called on join, on leave with null, and on dimension change
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

    // Brings up the section manager for a newly loaded world
    // The command list is opened around initRenderer because allocating the manager's GPU buffers needs one, and
    // the try-with-resources makes sure it is flushed and freed even if init throws
    protected void loadWorld(WORLD world) {
        this.world = world;

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.initRenderer(commandList);
        }
    }

    // Tears the section manager down and forgets the world
    // Ordered manager-then-world so nothing can observe a live manager pointing at a world that is already gone
    protected void unloadWorld() {
        if (this.renderSectionManager != null) {
            this.renderSectionManager.destroy();
            this.renderSectionManager = null;
        }

        this.world = null;
    }

    // Number of chunk sections the last graph update found visible in the camera frustum; the F3 "C:" figure
    public int getVisibleChunkCount() {
        return this.renderSectionManager.getVisibleChunkCount();
    }

    // Marks the visibility graph stale so the next setupTerrain rebuilds it
    // Called when something other than camera movement changed what is visible, e.g. a block update opening a
    // new sightline between sections
    public void scheduleTerrainUpdate() {
        // BUG: seems to be called before init
        if (this.renderSectionManager != null) {
            this.renderSectionManager.markGraphDirty();
        }
    }

    // True once nothing is queued for rebuild, i.e. the world is fully meshed
    // The loading screen waits on this before handing control to the player
    public boolean isTerrainRenderComplete() {
        return this.renderSectionManager.getBuilder().isBuildQueueEmpty();
    }

    public abstract int getEffectiveRenderDistance();

    // The per-pass entry point: runs before any chunk drawing and brings the section manager up to date
    // Reclaims retired native buffers first, then finishes any graph update still in flight, because everything
    // below assumes the manager is not mid-traversal
    // The `frame` parameter is deprecated and only still threaded through for the section manager's own use
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

    // Drains the chunk tracker's pending add/remove events into the section manager
    // Batched here rather than applied as they arrive so the manager only reshapes its section table once per
    // frame, on the render thread
    private void processChunkEvents() {
        var tracker = ChunkTrackerHolder.get(this.world);
        tracker.forEachEvent(this.renderSectionManager::onChunkAdded, this.renderSectionManager::onChunkRemoved);
    }

    protected abstract ChunkRenderMatrices createChunkRenderMatrices();

    // The viewport the most recent setupTerrain ran with; drawChunkLayer needs it for the occlusion camera
    public Viewport getLastViewport() {
        return this.currentViewport;
    }

    // Draws every visible section for one vanilla render layer
    // A layer can map to several terrain passes (solid and cutout share a layer, for instance), so the pass list
    // is looked up and each one drawn in order
    // Two cameras are handed down on purpose: the occlusion camera is the one the visibility graph was built
    // against, while the real camera is the caller's current position — they differ during the shadow pass and
    // whenever the graph is a frame stale, and using the wrong one either pops geometry or breaks sorting
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

    // Tears down and recreates the section manager, e.g. after a render distance change
    public void reload() {
        if (this.world == null) {
            return;
        }

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.initRenderer(commandList);
        }
    }

    protected abstract SECTIONMANAGER createRenderSectionManager(CommandList commandList);

    // Creates the section manager for the current world
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

    // Iterates the block entities in visible sections, plus those in sections flagged as holding global entities
    // (ones that render regardless of their own section's visibility, like beacons)
    // Lazy, so nothing is collected into a list for callers that stop early
    public Iterator<BLOCKENTITY> blockEntityIterator() {
        return MinecraftBuiltRenderSectionData.generateBlockEntityIterator(this.renderSectionManager.getRenderLists(), this.renderSectionManager.getSectionsWithGlobalEntities());
    }

    // Same traversal as blockEntityIterator but push-based, which avoids the iterator allocation on the path
    // that always visits everything
    public void forEachVisibleBlockEntity(Consumer<BLOCKENTITY> consumer) {
        MinecraftBuiltRenderSectionData.forEachBlockEntity(consumer, this.renderSectionManager.getRenderLists(), this.renderSectionManager.getSectionsWithGlobalEntities());
    }

    protected abstract void renderBlockEntityList(List<BLOCKENTITY> list, BLOCKENTITY_RENDER_CONTEXT context);

    // Block entities in visible sections only
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

    // Block entities that render regardless of distance, e.g. beacons
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

    // Both passes; returns the count for the debug screen
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

    // Whether the section containing a point was drawn
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

    // Whether any section a box overlaps was drawn
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

    // The C: line for the debug screen
    public String getChunksDebugString() {
        // C: visible/total D: distance
        return String.format("C: %d/%d D: %d %s", this.renderSectionManager.getVisibleChunkCount(),
                this.renderSectionManager.getTotalSections(), this.renderDistance,
                this.renderSectionManager.getTickerDebugString());
    }

    // The pass set in use
    public RenderPassConfiguration<?> getRenderPassConfiguration() {
        return this.renderSectionManager.getRenderPassConfiguration();
    }

    // Rebuilds every section overlapping a block-coordinate region
    // The >> 4 turns block coordinates into section coordinates; it is an arithmetic shift so negative
    // coordinates floor correctly, which plain division would not
    public void scheduleRebuildForBlockArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean important) {
        this.scheduleRebuildForChunks(minX >> 4, minY >> 4, minZ >> 4, maxX >> 4, maxY >> 4, maxZ >> 4, important);
    }

    // Rebuilds every section in an inclusive section-coordinate box
    // Bounds are inclusive on both ends, hence <=, because callers pass the min and max section actually touched
    public void scheduleRebuildForChunks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean important) {
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkY = minY; chunkY <= maxY; chunkY++) {
                for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                    this.scheduleRebuildForChunk(chunkX, chunkY, chunkZ, important);
                }
            }
        }
    }

    // Queues one section for remeshing
    // `important` puts it on the blocking queue that gets drained before the frame is drawn, which is what a
    // block the player just placed needs; everything else can wait a frame
    public void scheduleRebuildForChunk(int x, int y, int z, boolean important) {
        this.renderSectionManager.scheduleRebuild(x, y, z, important);
    }

    // Lines for the F3 overlay; the viewport block is skipped before the first setupTerrain, when it is still null
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

    // Whether a section has been built at least once
    public boolean isSectionReady(int x, int y, int z) {
        return this.renderSectionManager.isSectionBuilt(x, y, z);
    }

    // Implemented (by mixin) on the game's own WorldRenderer so anything holding one can reach ours
    // The impetus$ prefix keeps the injected method from colliding with a vanilla or third-party name
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
