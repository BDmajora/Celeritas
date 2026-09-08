package com.bdmajora.impetus.engine.impl.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import com.bdmajora.impetus.engine.impl.common.util.TimeUtil;
import com.bdmajora.impetus.engine.impl.gl.device.CommandList;
import com.bdmajora.impetus.engine.impl.gl.device.RenderDevice;
import com.bdmajora.impetus.engine.impl.gl.profiling.TimerQueryManager;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.ChunkBuildContext;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.ChunkBuildOutput;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.ChunkTaskOutput;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.executor.ChunkBuilder;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.executor.ChunkJobMetricsTracker;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.executor.ChunkJobResult;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.executor.ChunkJobCollector;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.tasks.ChunkBuilderSortTask;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import com.bdmajora.impetus.engine.impl.render.chunk.data.BuiltRenderSectionData;
import com.bdmajora.impetus.engine.impl.render.chunk.data.BuiltSectionMeshParts;
import com.bdmajora.impetus.engine.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.ChunkRenderList;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.RenderListManager;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.SectionTicker;
import com.bdmajora.impetus.engine.impl.render.chunk.lists.SortedRenderLists;
import com.bdmajora.impetus.engine.impl.render.chunk.metrics.RenderSectionMetricsTracker;
import com.bdmajora.impetus.engine.impl.render.chunk.occlusion.AsyncOcclusionMode;
import com.bdmajora.impetus.engine.impl.render.chunk.occlusion.VisibilityEncoding;
import com.bdmajora.impetus.engine.impl.render.chunk.region.RenderRegion;
import com.bdmajora.impetus.engine.impl.render.chunk.region.RenderRegionManager;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderFogComponent;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;
import com.bdmajora.impetus.engine.impl.render.viewport.CameraTransform;
import com.bdmajora.impetus.engine.impl.common.util.MathUtil;
import com.bdmajora.impetus.engine.impl.render.viewport.Viewport;
import com.bdmajora.impetus.engine.impl.util.PositionUtil;
import com.bdmajora.impetus.engine.impl.util.iterator.ByteIterator;
import com.bdmajora.impetus.engine.impl.render.chunk.sorting.TranslucentQuadAnalyzer;
import com.bdmajora.impetus.engine.impl.render.chunk.sorting.trigger.NormalPlanes;
import com.bdmajora.impetus.engine.impl.render.chunk.sorting.trigger.TranslucencyTriggerIndex;
import com.bdmajora.impetus.engine.impl.util.suppliers.ExpiringSupplier;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3ic;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public abstract class RenderSectionManager {
    /**
     * When true, the section manager will continuously mark all sections as needing to be remeshed whenever the
     * update queue empties.
     */
    protected static final boolean CONTINUOUSLY_REMESH_WORLD = false;

    private final ChunkBuilder builder;

    private final Thread renderThread = Thread.currentThread();

    private final RenderRegionManager regions;

    private final Long2ReferenceMap<RenderSection> sectionByPosition = new Long2ReferenceOpenHashMap<>();

    private final ConcurrentLinkedDeque<ChunkJobResult<? extends ChunkTaskOutput>> buildResults = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Runnable> asyncSubmittedTasks = new ConcurrentLinkedDeque<>();

    private final ChunkRenderer chunkRenderer;

    private final int renderDistance;

    protected @Nullable Vector3ic lastCameraPosition;
    protected Vector3d cameraPosition = new Vector3d();

    /**
     * Plane-crossing trigger index for translucency sorting: maps translucent geometry planes to the sections
     * owning them, so camera movement schedules re-sorts only where draw order can actually have changed.
     */
    private final TranslucencyTriggerIndex translucencyTriggerIndex = new TranslucencyTriggerIndex();

    /**
     * Dynamic sections without usable plane data (normal-count overflow); these keep the legacy coarse
     * movement-based re-sort heuristic.
     */
    private final ReferenceOpenHashSet<RenderSection> coarseTriggeredSections = new ReferenceOpenHashSet<>();

    /** Precise camera position of the previous trigger evaluation ({@code null} until the first frame). */
    private @Nullable Vector3d lastTriggerCameraPosition;

    /** Movement (squared) beyond which we skip plane tests and re-sort every dynamic section (teleports). */
    private static final double TELEPORT_DISTANCE_SQ = 16.0 * 16.0;

    @Getter
    private final RenderPassConfiguration<?> renderPassConfiguration;

    private final Set<TerrainRenderPass> disabledRenderPasses;

    private final int minSection, maxSection;

    protected final RenderListManager renderListManager;

    @Nullable
    protected final RenderListManager shadowRenderListManager;

    protected final ReferenceSet<RenderSection> sectionsWithGlobalEntities = new ReferenceOpenHashSet<>();

    private final Object2ObjectOpenHashMap<TerrainRenderPass, TimerQueryManager> renderPassDrawTimers = new Object2ObjectOpenHashMap<>();

    protected final ReferenceSet<RenderSection> sectionsRequestingUpdate = new ReferenceOpenHashSet<>();

    @Getter
    protected final ChunkJobMetricsTracker jobMetricsTracker = new ChunkJobMetricsTracker();

    @Getter
    protected final RenderSectionMetricsTracker sectionMetricsTracker = new RenderSectionMetricsTracker();

    @Deprecated
    public RenderSectionManager(RenderPassConfiguration<?> configuration, Supplier<ChunkBuildContext> contextSupplier,
                                BiFunction<RenderDevice, RenderPassConfiguration<?>, ChunkRenderer> chunkRenderer,
                                int renderDistance, CommandList commandList, int minSection, int maxSection,
                                int requestedThreads) {
        this(configuration, contextSupplier, chunkRenderer, renderDistance, commandList, minSection, maxSection, requestedThreads, false);
    }

    public RenderSectionManager(RenderPassConfiguration<?> configuration, Supplier<ChunkBuildContext> contextSupplier,
                                BiFunction<RenderDevice, RenderPassConfiguration<?>, ChunkRenderer> chunkRenderer,
                                int renderDistance, CommandList commandList, int minSection, int maxSection,
                                int requestedThreads, boolean hasShadowPass) {
        this.chunkRenderer = chunkRenderer.apply(RenderDevice.INSTANCE, configuration);

        this.renderPassConfiguration = configuration;

        this.builder = new ChunkBuilder(this::managedBlock, contextSupplier, requestedThreads);

        this.renderDistance = renderDistance;

        this.regions = new RenderRegionManager(commandList, this.renderPassConfiguration);

        this.minSection = minSection;
        this.maxSection = maxSection;
        this.renderListManager = new RenderListManager(this.minSection, this.maxSection, this.getAsyncOcclusionMode() == AsyncOcclusionMode.EVERYTHING, this.createSectionTicker());
        if (hasShadowPass) {
            this.shadowRenderListManager = new RenderListManager(this.minSection, this.maxSection, this.getAsyncOcclusionMode() != AsyncOcclusionMode.NONE, this.createSectionTicker());
        } else {
            this.shadowRenderListManager = null;
        }

        this.disabledRenderPasses = new ReferenceArraySet<>();
    }

    protected abstract AsyncOcclusionMode getAsyncOcclusionMode();

    protected @Nullable SectionTicker createSectionTicker() {
        return null;
    }

    public void managedBlock(BooleanSupplier isDone) {
        while (!isDone.getAsBoolean()) {
            Runnable task = this.asyncSubmittedTasks.poll();
            if (task != null) {
                task.run();
            } else {
                LockSupport.parkNanos("Wait", 100000L);
            }
        }
    }

    public void runAsyncTasks() {
        Runnable task;

        while ((task = this.asyncSubmittedTasks.poll()) != null) {
            task.run();
        }

        this.renderPassDrawTimers.values().forEach(TimerQueryManager::updateTime);
    }

    /**
     * Whether terrain is being rendered for shadows.
     */
    public boolean isInShadowPass() {
        return false;
    }

    protected boolean isDebugInfoShown() {
        return false;
    }

    public void update(Viewport positionedViewport, int frame, boolean spectator) {
        if (isInShadowPass()) {
            // Umbra parity. `ShadowRenderer` wraps the whole shadow pass in `CullingDataCache#saveState`/
            // `restoreState`, which swaps out the visible-section list AND the camera memo so the shadow frustum
            // never becomes the renderer's idea of where the camera is. The list is already separated here (see
            // `getCurrentRenderListManager`), but `cameraPosition` and `lastCameraPosition` are single fields
            // shared by both passes, and the shadow pass runs first every frame while the camera pass calls this
            // method only when its graph is dirty. Writing them here therefore left the manager holding the
            // SHADOW viewport's position for the rest of any frame the camera pass skipped — which is what
            // `shouldPrioritizeRebuild` and `createSortTask` then measured distances against.
            //
            // `createTerrainRenderList` takes the viewport as a parameter and reads neither field, so the shadow
            // pass simply does not write them.
            this.createTerrainRenderList(positionedViewport, frame, spectator);
            return;
        }

        this.lastCameraPosition = positionedViewport.getBlockCoord();
        var transform = positionedViewport.getTransform();
        this.cameraPosition = new Vector3d(transform.x, transform.y, transform.z);

        this.createTerrainRenderList(positionedViewport, frame, spectator);

        this.checkTranslucencyChange();

        this.getCurrentRenderListManager().setNeedsUpdate(false);
    }

    private void checkTranslucencyChange() {
        if(lastCameraPosition == null)
            return;

        var previous = this.lastTriggerCameraPosition;
        this.lastTriggerCameraPosition = new Vector3d(this.cameraPosition);

        if (previous != null && !previous.equals(this.cameraPosition)) {
            if (previous.distanceSquared(this.cameraPosition) > TELEPORT_DISTANCE_SQ) {
                // Large jumps (teleports, dimension-ish moves) cross too many planes to be worth testing.
                this.translucencyTriggerIndex.forEachSection(section -> section.pendingTriggeredSort = true);
            } else {
                this.translucencyTriggerIndex.collectTriggered(
                        previous.x, previous.y, previous.z,
                        this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z,
                        section -> section.pendingTriggeredSort = true);
            }
        }

        int camSectionX = PositionUtil.posToSectionCoord(cameraPosition.x);
        int camSectionY = PositionUtil.posToSectionCoord(cameraPosition.y);
        int camSectionZ = PositionUtil.posToSectionCoord(cameraPosition.z);

        this.scheduleTranslucencyUpdates(camSectionX, camSectionY, camSectionZ);
    }

    private void scheduleTranslucencyUpdates(int camSectionX, int camSectionY, int camSectionZ) {
        var renderListManager = this.getCurrentRenderListManager();
        var rebuildLists = renderListManager.getRebuildLists().byUpdateType();
        var sortRebuildList = rebuildLists.get(ChunkUpdateType.SORT);
        var importantSortRebuildList = rebuildLists.get(ChunkUpdateType.IMPORTANT_SORT);
        var allowImportant = allowImportantRebuilds();
        var translucentPass = this.renderPassConfiguration.defaultTranslucentMaterial().pass;
        if (!this.hasTranslucencySortedSections()) {
            return;
        }
        for (Iterator<ChunkRenderList> it = renderListManager.getRenderLists().iterator(); it.hasNext(); ) {
            ChunkRenderList entry = it.next();
            var region = entry.getRegion();
            if (!region.hasSectionsInPass(translucentPass)) {
                continue;
            }
            ByteIterator sectionIterator = entry.sectionsWithGeometryIterator(false);
            if (sectionIterator == null) {
                continue;
            }
            while (sectionIterator.hasNext()) {
                var section = region.getSection(sectionIterator.nextByteAsInt());

                if (section == null || !section.isNeedsDynamicTranslucencySorting()) {
                    // Sections without sortable translucent data are not relevant
                    continue;
                }

                ChunkUpdateType update = ChunkUpdateType.getPromotionUpdateType(section.getPendingUpdate(), (allowImportant && this.shouldPrioritizeRebuild(section)) ? ChunkUpdateType.IMPORTANT_SORT : ChunkUpdateType.SORT);

                if (update == null) {
                    // We wouldn't be able to resort this section anyway
                    continue;
                }

                boolean triggered = section.pendingTriggeredSort;

                if (!triggered && this.coarseTriggeredSections.contains(section)) {
                    // Legacy heuristic, kept only for sections whose plane data overflowed: re-sort after moving
                    // at least one block while crossing the section grid or its axes.
                    double dx = cameraPosition.x - section.lastCameraX;
                    double dy = cameraPosition.y - section.lastCameraY;
                    double dz = cameraPosition.z - section.lastCameraZ;
                    double camDelta = (dx * dx) + (dy * dy) + (dz * dz);

                    if (camDelta >= 1) {
                        boolean cameraChangedSection = camSectionX != PositionUtil.posToSectionCoord(section.lastCameraX) ||
                                camSectionY != PositionUtil.posToSectionCoord(section.lastCameraY) ||
                                camSectionZ != PositionUtil.posToSectionCoord(section.lastCameraZ);

                        triggered = cameraChangedSection || section.isAlignedWithSectionOnGrid(camSectionX, camSectionY, camSectionZ);
                    }
                }

                if (triggered) {
                    section.setPendingUpdate(update);
                    // Inject it into the rebuild lists
                    (update == ChunkUpdateType.IMPORTANT_SORT ? importantSortRebuildList : sortRebuildList).add(section);

                    section.pendingTriggeredSort = false;
                    section.lastCameraX = cameraPosition.x;
                    section.lastCameraY = cameraPosition.y;
                    section.lastCameraZ = cameraPosition.z;
                }
            }
        }
    }

    /**
     * {@return true if the renderer should respect per-frame queue limits and not try to update as many chunks as
     * possible per frame}
     */
    protected boolean shouldRespectUpdateTaskQueueSizeLimit() {
        return true;
    }

    private void createTerrainRenderList(Viewport viewport, int frame, boolean spectator) {
        final var searchDistance = this.getSearchDistance();
        final var useOcclusionCulling = this.shouldUseOcclusionCulling(viewport, spectator);
        final int targetQueueSize;

        if (this.shouldRespectUpdateTaskQueueSizeLimit()) {
            targetQueueSize = (int)Math.min(Integer.MAX_VALUE, (long)this.builder.getTargetQueueSize() * 10);
        } else {
            targetQueueSize = Integer.MAX_VALUE;
        }

        this.getCurrentRenderListManager().startGraphUpdate(viewport, frame, this.regions.getRegionIdsLength(),
                searchDistance, useOcclusionCulling, targetQueueSize);
    }

    protected abstract boolean useFogOcclusion();

    private float getSearchDistance() {
        float distance;

        if (this.useFogOcclusion()) {
            distance = this.getEffectiveRenderDistance();
        } else {
            distance = this.getRenderDistance();
        }

        return distance;
    }

    protected abstract boolean shouldUseOcclusionCulling(Viewport viewport, boolean spectator);

    private boolean hasTranslucencySortedSections() {
        return this.getCurrentRenderListManager().getRenderLists().getPasses().stream().anyMatch(TerrainRenderPass::isSorted);
    }

    protected abstract boolean isSectionVisuallyEmpty(int x, int y, int z);

    public void onSectionAdded(int x, int y, int z) {
        long key = PositionUtil.packSection(x, y, z);

        if (this.sectionByPosition.containsKey(key)) {
            return;
        }

        RenderRegion region = this.regions.createForChunk(x, y, z);

        RenderSection renderSection = new RenderSection(region, x, y, z);
        region.addSection(renderSection);

        this.sectionByPosition.put(key, renderSection);

        this.renderListManager.attachRenderSection(renderSection);
        if (this.shadowRenderListManager != null) {
            this.shadowRenderListManager.attachRenderSection(renderSection);
        }

        this.invalidateCachedSectionData(renderSection);

        if (this.isSectionVisuallyEmpty(x, y, z)) {
            this.updateSectionInfo(renderSection, RenderSection.EMPTY_DATA);
        } else {
            renderSection.setPendingUpdate(ChunkUpdateType.INITIAL_BUILD);
        }


        this.markGraphDirty();
    }

    public void onSectionRemoved(int x, int y, int z) {
        RenderSection section = this.sectionByPosition.remove(PositionUtil.packSection(x, y, z));

        if (section == null) {
            return;
        }

        RenderRegion region = section.getRegion();

        if (region != null) {
            region.removeSection(section);
        }

        this.invalidateCachedSectionData(section);

        this.updateSectionInfo(section, null);

        this.renderListManager.detachRenderSection(section);
        if (this.shadowRenderListManager != null) {
            this.shadowRenderListManager.detachRenderSection(section);
        }

        this.sectionMetricsTracker.removeSection(section);

        this.translucencyTriggerIndex.remove(section);
        this.coarseTriggeredSections.remove(section);

        section.delete();

        this.markGraphDirty();
    }

    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, CameraTransform occlusionCamera, CameraTransform camera) {
        if (disabledRenderPasses.contains(pass)) {
            return;
        }

        RenderDevice device = RenderDevice.INSTANCE;
        CommandList commandList = device.createCommandList();

        boolean shouldProfile = isDebugInfoShown();

        TimerQueryManager timer = null;

        if (shouldProfile) {
            timer = renderPassDrawTimers.computeIfAbsent(pass, $ -> new TimerQueryManager());
            timer.startProfiling();
        }

        this.chunkRenderer.render(matrices, commandList, this.getCurrentRenderListManager().getRenderLists(), pass, occlusionCamera, camera);

        if (shouldProfile) {
            timer.finishProfiling();
        }

        commandList.flush();
    }

    public boolean isSectionVisible(int x, int y, int z) {
        return this.getCurrentRenderListManager().isSectionVisible(x, y, z);
    }

    private boolean rebuildListHasUpdates() {
        for (var queue : this.getCurrentRenderListManager().getRebuildLists().byUpdateType().values()) {
            if (!queue.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inject sections that requested a rebuild between graph updates into the appropriate rebuild lists.
     */
    private void promoteInterimRebuildList() {
        var rebuildLists = this.getCurrentRenderListManager().getRebuildLists().byUpdateType();
        for (var section : this.sectionsRequestingUpdate) {
            rebuildLists.get(section.getPendingUpdate()).add(section);
        }
    }

    public void updateChunks(boolean updateImmediately) {
        this.regions.update();
        this.jobMetricsTracker.tick();

        // Advance the adaptive scheduling controller once per frame, before any dispatch reads the budget. This
        // runs only on the main terrain pass so that an additional shadow pass in the same frame does not
        // double-tick the controller; both passes share the same worker queue and in-flight target.
        boolean mainPass = !this.isInShadowPass();

        if (mainPass) {
            this.builder.tickSchedulingBudget();
        }

        // Promotion of the interim rebuild list is not required if a graph update is requested, as the graph
        // generates a new rebuild list anyway.
        //
        // Main pass only. `sectionsRequestingUpdate` is main-pass state: it is filled under
        // `!getCurrentRenderListManager().isNeedsUpdate()` and drained here, and the "clearing is safe because the
        // graph will regenerate the list" argument only holds for the manager that is about to run a graph update.
        //
        // SimpleWorldRenderer#setupTerrain no longer calls this method during the Umbra shadow pass at all (that
        // pass is culling-only, matching Umbra's ShadowRenderer, which never runs vanilla's chunk build dispatch),
        // so `mainPass` is true for every caller today. The guards here and on `tickSchedulingBudget()` /
        // `setDispatchBudgetLimited()` are kept as a backstop: everything in this method is scoped to whichever
        // manager is current, so reaching it from the shadow pass drains the shadow list's rebuild queue and spends
        // the shared ChunkBuilder budget that the camera pass needs. That starves terrain on a streaming world —
        // geometry drains away while entities, which do not come from these lists, keep drawing.
        if (mainPass) {
            if (!this.renderListManager.isNeedsUpdate() && !sectionsRequestingUpdate.isEmpty()) {
                this.promoteInterimRebuildList();
            }

            this.sectionsRequestingUpdate.clear();
        }

        if (!rebuildListHasUpdates()) {
            // Nothing was dispatched, so the workers cannot have been starved for lack of budget.
            if (mainPass) {
                this.builder.setDispatchBudgetLimited(false);
            }
            if (CONTINUOUSLY_REMESH_WORLD && !this.getCurrentRenderListManager().getRebuildLists().hasAdditionalUpdates()) {
                this.scheduleRebuildAll();
            }
            return;
        }

        var blockingRebuilds = new ChunkJobCollector(Integer.MAX_VALUE, this.buildResults::add);
        var deferredRebuilds = new ChunkJobCollector(this.builder.getSchedulingBudget(), this.buildResults::add);

        this.submitRebuildTasks(blockingRebuilds, ChunkUpdateType.IMPORTANT_REBUILD);
        this.submitRebuildTasks(blockingRebuilds, ChunkUpdateType.IMPORTANT_SORT);

        // Track whether the deferred dispatch was throttled by the budget while work still
        // remained. Combined with worker starvation, this is what tells the controller to grow the in-flight
        // target next frame.
        boolean budgetLimited = false;
        budgetLimited |= this.submitRebuildTasks(updateImmediately ? blockingRebuilds : deferredRebuilds, ChunkUpdateType.REBUILD);
        budgetLimited |= this.submitRebuildTasks(updateImmediately ? blockingRebuilds : deferredRebuilds, ChunkUpdateType.INITIAL_BUILD);
        // The BFS itself may have discarded candidates that did not fit in the rebuild lists; that is also work
        // we were unable to dispatch this frame.
        budgetLimited |= this.getCurrentRenderListManager().getRebuildLists().hasAdditionalUpdates();
        if (mainPass) {
            this.builder.setDispatchBudgetLimited(budgetLimited);
        }

        // Count sort tasks as requiring a quarter of the resources of a mesh task
        var deferredSorts = new ChunkJobCollector(Math.max(4, this.builder.getSchedulingBudget() * 4), this.buildResults::add);
        this.submitRebuildTasks(updateImmediately ? blockingRebuilds : deferredSorts, ChunkUpdateType.SORT);

        blockingRebuilds.awaitCompletion(this.builder);

        // Tick singlethreaded rebuilds
        this.builder.tick();
    }

    public void uploadChunks() {
        var results = this.collectChunkBuildResults();

        if (results.isEmpty()) {
            return;
        }

        // Ensure occlusion threads are stopped at this point, as we're about to mutate render section data.
        this.finishAllGraphUpdates();

        this.processChunkBuildResults(results);

        for (var result : results) {
            result.output().delete();
        }

        // Forcefully mark the graph as needing updates if the previous render list detected an overflow of the
        // update queue. This is necessary to queue those additional chunks.
        if (this.getCurrentRenderListManager().getRebuildLists().hasAdditionalUpdates()) {
            this.markGraphDirty();
        }
    }

    public final void tickVisibleRenders() {
        this.getCurrentRenderListManager().tickVisibleRenders();
    }

    private void processChunkBuildResults(ArrayList<ChunkJobResult.Success<? extends ChunkTaskOutput>> results) {
        var filtered = filterChunkBuildResults(results);

        this.regions.uploadMeshes(RenderDevice.INSTANCE.createCommandList(), filtered, this::markGraphDirty);

        for (var holder : filtered) {
            var result = holder.output();
            if (result instanceof ChunkBuildOutput buildResult) {
                boolean changed = this.updateSectionInfo(result.render, buildResult.info);

                if (changed) {
                    // The chunk graph must be rebuilt if the render section reports the info has changed. This
                    // could indicate an occlusion data update, block entity addition/removal, animated texture
                    // change, etc.
                    this.markGraphDirty();
                }

                // We only change the translucency info on full rebuilds, as sorts can keep using the same data
                this.updateTranslucencyInfo(result.render, buildResult.meshes);
            }

            var job = result.render.getBuildCancellationToken();

            // Only clear the token if this result belongs to the most recently submitted build.
            // A stale result from an earlier submission must not clear the token for a newer
            // in-flight job, which is identified by a higher lastSubmittedFrame.
            if (job != null && result.buildTime >= result.render.getLastSubmittedFrame()) {
                result.render.setBuildCancellationToken(null);
            }

            result.render.setLastBuiltFrame(result.buildTime);
            this.sectionMetricsTracker.updateSectionBuildDuration(result.render, holder.executionTimeNanos());
        }
    }

    private void updateTranslucencyInfo(RenderSection render, Map<TerrainRenderPass, BuiltSectionMeshParts> meshes) {
        Map<TerrainRenderPass, TranslucentQuadAnalyzer.SortState> sortStates = new Reference2ObjectArrayMap<>();
        for(var entry : meshes.entrySet()) {
            if(entry.getKey().isSorted()) {
                sortStates.put(entry.getKey(), Objects.requireNonNull(entry.getValue().sortState()).compactForStorage());
            }
        }
        render.setTranslucencySortStates(sortStates.isEmpty() ? Collections.emptyMap() : sortStates);

        this.updateTranslucencyTriggerRegistration(render, sortStates);
    }

    /**
     * (Re-)registers a section with the plane-crossing trigger index. Dynamic sections whose plane data
     * overflowed (or predates this mechanism) fall back to the legacy movement heuristic instead.
     */
    private void updateTranslucencyTriggerRegistration(RenderSection render, Map<TerrainRenderPass, TranslucentQuadAnalyzer.SortState> sortStates) {
        NormalPlanes[] planes = null;
        boolean dynamic = false;
        boolean missingPlanes = false;

        for (var state : sortStates.values()) {
            if (!state.requiresDynamicSorting()) {
                continue;
            }

            dynamic = true;

            var statePlanes = state.triggerPlanes();

            if (statePlanes == null) {
                missingPlanes = true;
            } else if (planes == null) {
                planes = statePlanes;
            } else {
                var merged = new NormalPlanes[planes.length + statePlanes.length];
                System.arraycopy(planes, 0, merged, 0, planes.length);
                System.arraycopy(statePlanes, 0, merged, planes.length, statePlanes.length);
                planes = merged;
            }
        }

        render.pendingTriggeredSort = false;

        if (dynamic && !missingPlanes && planes != null) {
            this.translucencyTriggerIndex.update(render, planes);
            this.coarseTriggeredSections.remove(render);
        } else {
            this.translucencyTriggerIndex.remove(render);

            if (dynamic) {
                this.coarseTriggeredSections.add(render);
            } else {
                this.coarseTriggeredSections.remove(render);
            }
        }
    }

    @MustBeInvokedByOverriders
    protected boolean updateSectionInfo(RenderSection render, @Nullable BuiltRenderSectionData info) {
        boolean changed = render.setInfo(info);

        if (changed) {
            long visibilityData = info != null ? info.visibilityData : VisibilityEncoding.NULL;
            this.renderListManager.updateVisibilityData(render.getChunkX(), render.getChunkY(), render.getChunkZ(), visibilityData);
            if (this.shadowRenderListManager != null) {
                this.shadowRenderListManager.updateVisibilityData(render.getChunkX(), render.getChunkY(), render.getChunkZ(), visibilityData);
            }

            if (!(info instanceof MinecraftBuiltRenderSectionData<?, ?> data)) {
                this.sectionsWithGlobalEntities.remove(render);
            } else if (!data.globalBlockEntities.isEmpty()) {
                this.sectionsWithGlobalEntities.add(render);
            }
        }

        return changed;
    }

    private static List<ChunkJobResult.Success<? extends ChunkTaskOutput>> filterChunkBuildResults(ArrayList<ChunkJobResult.Success<? extends ChunkTaskOutput>> outputs) {
        var map = new Reference2ReferenceLinkedOpenHashMap<RenderSection, ChunkJobResult.Success<? extends ChunkTaskOutput>>();

        for (var holder : outputs) {
            var output = holder.output();
            if (output.render.isDisposed() || output.render.getLastBuiltFrame() > output.buildTime) {
                continue;
            }

            var render = output.render;
            var previousHolder = map.get(render);

            if (previousHolder == null || previousHolder.output().buildTime < output.buildTime) {
                map.put(render, holder);
            }
        }

        return new ArrayList<>(map.values());
    }

    private ArrayList<ChunkJobResult.Success<? extends ChunkTaskOutput>> collectChunkBuildResults() {
        ArrayList<ChunkJobResult.Success<? extends ChunkTaskOutput>> results = new ArrayList<>();
        ChunkJobResult<? extends ChunkTaskOutput> result;

        while ((result = this.buildResults.poll()) != null) {
            if (result instanceof ChunkJobResult.Success<? extends ChunkTaskOutput> successfulResult) {
                this.jobMetricsTracker.collectMetrics(successfulResult);
                results.add(successfulResult);
            } else if (result instanceof ChunkJobResult.Failure<? extends ChunkTaskOutput> failure) {
                failure.abort();
            } else {
                throw new AssertionError();
            }
        }

        return results;
    }

    /**
     * {@return true if dispatch stopped because the collector's budget was exhausted while sections still
     * remained in the queue, i.e. dispatch was budget-limited rather than work-limited for this update type}
     */
    private boolean submitRebuildTasks(ChunkJobCollector collector, ChunkUpdateType type) {
        var queue = this.getCurrentRenderListManager().getRebuildLists().byUpdateType().get(type);

        int frame = this.getCurrentRenderListManager().getLastUpdatedFrame();

        while (!queue.isEmpty() && collector.canOffer()) {
            RenderSection section = queue.remove();

            if (section.isDisposed()) {
                continue;
            }

            // The pending update type may have changed since this entry was queued. Cases:
            //   - A SORT was promoted to REBUILD (e.g. a block changed while a sort was pending):
            //     the section remains in the SORT queue but pendingUpdate is now REBUILD, so the
            //     SORT pass skips it and the REBUILD pass picks it up correctly.
            //   - The type was cleared by a prior pass in the same frame.
            //   - The type was set to null after the async BFS generated the list (authoritative
            //     guard against double submissions from a stale buildCancellationToken read).
            if (section.getPendingUpdate() != type) {
                continue;
            }

            ChunkBuilderTask<? extends ChunkTaskOutput> task = type.isSort() ? this.createSortTask(section, frame) : this.createRebuildTask(section, frame);

            if (task == null && type.isSort()) {
                // Ignore sorts that became invalid
                section.setPendingUpdate(null);
                continue;
            }

            if (task != null) {
                var job = this.builder.scheduleTask(task, type.isImportant(), collector::onJobFinished);
                collector.addSubmittedJob(job);

                section.setBuildCancellationToken(job);

                if (!type.isSort()) {
                    // Prevent further sorts from being performed on this section
                    section.setNeedsDynamicTranslucencySorting(false);
                }
            } else {
                var result = new ChunkJobResult.Success<>(new ChunkBuildOutput(section, RenderSection.EMPTY_DATA, Reference2ReferenceMaps.emptyMap(), frame), -1);
                this.buildResults.add(result);

                section.setBuildCancellationToken(null);
            }

            section.setLastSubmittedFrame(frame);
            section.setPendingUpdate(null);
        }

        // The loop only exits early on !canOffer(), so leftover sections mean we ran out of budget, not work.
        return !queue.isEmpty();
    }

    protected abstract @Nullable ChunkBuilderTask<ChunkBuildOutput> createRebuildTask(RenderSection render, int frame);

    public ChunkBuilderSortTask createSortTask(RenderSection render, int frame) {
        if(!render.isNeedsDynamicTranslucencySorting())
            return null;
        return new ChunkBuilderSortTask(render, (float)cameraPosition.x, (float)cameraPosition.y, (float)cameraPosition.z, frame, render.getTranslucencySortStates(), this.renderPassConfiguration);
    }

    public void markGraphDirty() {
        if (this.shadowRenderListManager != null) {
            this.shadowRenderListManager.setNeedsUpdate(true);
        }
        this.renderListManager.setNeedsUpdate(true);
    }

    public void finishAllGraphUpdates() {
        this.renderListManager.finishPreviousGraphUpdate();
        if (this.shadowRenderListManager != null) {
            this.shadowRenderListManager.finishPreviousGraphUpdate();
        }
    }

    public boolean needsUpdate() {
        return this.getCurrentRenderListManager().isNeedsUpdate();
    }

    public ChunkBuilder getBuilder() {
        return this.builder;
    }

    public void destroy() {
        this.finishAllGraphUpdates();

        this.builder.shutdown(); // stop all the workers, and cancel any tasks

        for (var result : this.collectChunkBuildResults()) {
            result.output().delete(); // delete resources for any pending tasks (including those that were cancelled)
        }

        this.renderListManager.destroy();
        if (this.shadowRenderListManager != null) {
            this.shadowRenderListManager.destroy();
        }

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.regions.delete(commandList);
            this.chunkRenderer.delete(commandList);
        }

        this.renderPassDrawTimers.values().forEach(TimerQueryManager::close);
        this.renderPassDrawTimers.clear();

        this.sectionsWithGlobalEntities.clear();

        this.translucencyTriggerIndex.clear();
        this.coarseTriggeredSections.clear();
    }

    public int getTotalSections() {
        return this.sectionByPosition.size();
    }

    public int getVisibleChunkCount() {
        var sections = 0;
        var iterator = this.getCurrentRenderListManager().getRenderLists().iterator();

        while (iterator.hasNext()) {
            var renderList = iterator.next();
            sections += renderList.getSectionsWithGeometryCount();
        }

        return sections;
    }

    public final void scheduleAsyncTask(Runnable runnable) {
        if (Thread.currentThread() == this.renderThread) {
            // Run immediately, otherwise the thread may deadlock waiting for itself
            runnable.run();
        } else {
            asyncSubmittedTasks.add(runnable);
        }
    }

    private void scheduleRebuildOffThread(int x, int y, int z, boolean important) {
        scheduleAsyncTask(() -> this.scheduleSectionForRebuild(x, y, z, important));
    }

    public final void scheduleRebuild(int x, int y, int z, boolean important) {
        if (Thread.currentThread() != renderThread) {
            this.scheduleRebuildOffThread(x, y, z, important);
            return;
        }

        this.scheduleSectionForRebuild(x, y, z, important);
    }

    protected void invalidateCachedSectionData(RenderSection section) {

    }

    protected void scheduleSectionForRebuild(int x, int y, int z, boolean important) {
        RenderSection section = this.sectionByPosition.get(PositionUtil.packSection(x, y, z));

        if (section != null) {
            this.invalidateCachedSectionData(section);

            ChunkUpdateType pendingUpdate;

            if (allowImportantRebuilds() && (important || this.shouldPrioritizeRebuild(section))) {
                pendingUpdate = ChunkUpdateType.IMPORTANT_REBUILD;
            } else {
                pendingUpdate = ChunkUpdateType.REBUILD;
            }

            if (section.requestUpdate(pendingUpdate)) {
                if (!this.getCurrentRenderListManager().isNeedsUpdate() && this.sectionsRequestingUpdate.size() < this.builder.getSchedulingBudget()) {
                    this.sectionsRequestingUpdate.add(section);
                } else {
                    this.markGraphDirty();
                }
            }
        }
    }

    public void scheduleRebuildAll() {
        for (var section : this.sectionByPosition.values()) {
            if (!this.isSectionVisuallyEmpty(section.getChunkX(), section.getChunkY(), section.getChunkZ())) {
                this.invalidateCachedSectionData(section);
                section.requestUpdate(ChunkUpdateType.REBUILD);
            }
        }
        this.markGraphDirty();
    }

    private static final float NEARBY_REBUILD_DISTANCE = MathUtil.square(16.0f);

    private boolean shouldPrioritizeRebuild(RenderSection section) {
        return this.lastCameraPosition != null && section.getSquaredDistanceFromBlockCenter(this.lastCameraPosition.x(), this.lastCameraPosition.y(), this.lastCameraPosition.z()) < NEARBY_REBUILD_DISTANCE;
    }

    /**
     * {@return true if rebuilds of chunks near the player should block the main thread, reduces flickering but will
     * potentially cause lag spikes}
     */
    protected boolean allowImportantRebuilds() {
        return false;
    }

    private float getEffectiveRenderDistance() {
        var color = ChunkShaderFogComponent.FOG_SERVICE.getFogColor();
        var alpha = color[3];
        var distance = ChunkShaderFogComponent.FOG_SERVICE.getFogCutoff();

        var renderDistance = this.getRenderDistance();

        // The fog must be fully opaque in order to skip rendering of chunks behind it
        if (Math.abs(alpha - 1.0f) >= 1.0E-5F) {
            return renderDistance;
        }

        return Math.min(renderDistance, distance + 0.5f);
    }

    private float getRenderDistance() {
        return this.renderDistance * 16.0f;
    }

    private RenderSection getRenderSection(int x, int y, int z) {
        return this.sectionByPosition.get(PositionUtil.packSection(x, y, z));
    }

    public Collection<RenderSection> getAllRenderSections() {
        return Collections.unmodifiableCollection(this.sectionByPosition.values());
    }

    private Collection<String> getSortingStrings() {
        List<String> list = new ArrayList<>();

        int[] sectionCounts = new int[TranslucentQuadAnalyzer.Level.VALUES.length];

        for (Iterator<ChunkRenderList> it = this.getCurrentRenderListManager().getRenderLists().iterator(); it.hasNext(); ) {
            var renderList = it.next();
            var region = renderList.getRegion();
            var listIter = renderList.sectionsWithGeometryIterator(false);
            if(listIter != null) {
                while(listIter.hasNext()) {
                    RenderSection section = region.getSection(listIter.nextByteAsInt());
                    // Do not count sections without translucent data
                    if(section == null || section.getTranslucencySortStates().isEmpty()) {
                        continue;
                    }

                    sectionCounts[section.getHighestSortingLevel().ordinal()]++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Sorting: ");
        TranslucentQuadAnalyzer.Level[] values = TranslucentQuadAnalyzer.Level.VALUES;
        for (int i = 0; i < values.length; i++) {
            TranslucentQuadAnalyzer.Level level = values[i];
            sb.append(level.name());
            sb.append('=');
            sb.append(sectionCounts[level.ordinal()]);
            if((i + 1) < values.length) {
                sb.append(", ");
            }
        }

        list.add(sb.toString());

        return list;
    }

    private Object2LongMap<TerrainRenderPass> computeRenderPassTimingsMap() {
        Object2LongOpenHashMap<TerrainRenderPass> map = new Object2LongOpenHashMap<>();
        for (var entry : renderPassDrawTimers.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getLastTime());
        }
        return map;
    }

    protected final Supplier<Object2LongMap<TerrainRenderPass>> renderPassTimingsDebounced = new ExpiringSupplier<>(this::computeRenderPassTimingsMap, 1, TimeUnit.SECONDS);

    public Collection<String> getDebugStrings() {
        List<String> list = new ArrayList<>();

        int count = 0, indexCount = 0;

        long deviceUsed = 0;
        long deviceAllocated = 0;

        long indexUsed = 0, indexAllocated = 0;

        for (var region : this.regions.getLoadedRegions()) {
            for (var resources : region.getAllResources()) {
                var buffer = resources.getGeometryArena();

                deviceUsed += buffer.getDeviceUsedMemoryL();
                deviceAllocated += buffer.getDeviceAllocatedMemoryL();

                var indexBuffer = resources.getIndexArena();

                if (indexBuffer != null) {
                    indexUsed += indexBuffer.getDeviceUsedMemoryL();
                    indexAllocated += indexBuffer.getDeviceAllocatedMemoryL();
                    indexCount++;
                }

                count++;
            }
        }

        list.add(String.format("G: %d/%d, I: %d/%d MiB (%d buffers)", MathUtil.toMib(deviceUsed), MathUtil.toMib(deviceAllocated), MathUtil.toMib(indexUsed), MathUtil.toMib(indexAllocated), count));
        list.add(String.format("Transfer Queue: %s", this.regions.getStagingBuffer().toString()));

        var uploadEstimator = this.regions.getUploadDurationEstimator();
        long lastUploadBytes = uploadEstimator.getLastUploadBytes();

        if (lastUploadBytes > 0L) {
            list.add(String.format("Upload Estimate: %d KiB, predicted %s, last %s",
                    lastUploadBytes / 1024L,
                    TimeUtil.stringifyTime(uploadEstimator.getLastUploadEstimateNanos(), TimeUnit.NANOSECONDS),
                    TimeUtil.stringifyTime(uploadEstimator.getLastUploadDurationNanos(), TimeUnit.NANOSECONDS)));
        }

        var rebuildLists = this.getCurrentRenderListManager().getRebuildLists();

        list.add(String.format("Chunk Queues: U=%02d (P0=%03d | P1=%03d | P2=%03d)",
                this.buildResults.size(),
                rebuildLists.getUpdateCount(ChunkUpdateType.IMPORTANT_REBUILD),
                rebuildLists.getUpdateCount(ChunkUpdateType.REBUILD),
                rebuildLists.getUpdateCount(ChunkUpdateType.INITIAL_BUILD)
        ));

        var debugStats = renderListManager.getDebugStatistics();

        var counts = debugStats.renderPassCounts().object2IntEntrySet().stream().sorted(Comparator.comparingInt(e -> -e.getIntValue())).iterator();

        var timingMap = renderPassTimingsDebounced.get();

        while (counts.hasNext()) {
            var entry = counts.next();
            var duration = timingMap.getLong(entry.getKey());
            String time;
            if (duration == 0) {
                time = "?? ms";
            } else {
                time = TimeUtil.stringifyTime(duration, TimeUnit.NANOSECONDS);
            }

            list.add(entry.getKey().name() + " - " + entry.getIntValue() + " sections, " + time);
        }

        if (renderListManager.getRenderLists().getPasses().stream().anyMatch(TerrainRenderPass::isSorted)) {
            list.add(debugStats.getSortingString());
        }

        return list;
    }

    private RenderListManager getCurrentRenderListManager() {
        return isInShadowPass() ? this.shadowRenderListManager : this.renderListManager;
    }

    public SortedRenderLists getRenderLists() {
        return this.getCurrentRenderListManager().getRenderLists();
    }

    public boolean isSectionBuilt(int x, int y, int z) {
        var section = this.getRenderSection(x, y, z);
        return section != null && section.isBuilt();
    }

    public void onChunkAdded(int x, int z) {
        for (int y = this.minSection; y < this.maxSection; y++) {
            this.onSectionAdded(x, y, z);
        }
    }

    public void onChunkRemoved(int x, int z) {
        for (int y = this.minSection; y < this.maxSection; y++) {
            this.onSectionRemoved(x, y, z);
        }
    }

    public void toggleRenderingForTerrainPass(TerrainRenderPass pass) {
        if(this.disabledRenderPasses.contains(pass)) {
            this.disabledRenderPasses.remove(pass);
        } else {
            this.disabledRenderPasses.add(pass);
        }
    }

    public final Collection<RenderSection> getSectionsWithGlobalEntities() {
        return ReferenceSets.unmodifiable(this.sectionsWithGlobalEntities);
    }

    public String getTickerDebugString() {
        return this.getCurrentRenderListManager().getTickerDebugString();
    }
}
