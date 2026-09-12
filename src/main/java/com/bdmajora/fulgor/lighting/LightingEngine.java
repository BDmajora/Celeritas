package com.bdmajora.fulgor.lighting;

import com.bdmajora.fulgor.Fulgor;
import com.bdmajora.fulgor.FulgorConfig;
import com.bdmajora.fulgor.api.ChunkLightingData;
import com.bdmajora.fulgor.collections.DeduplicatedLongQueue;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.concurrent.locks.ReentrantLock;

// Batched light propagator replacing vanilla's recursive checkLightFor, one per World (from Phosphor); positions are recorded until something reads light, then the batch runs brightest-to-darkest, keys packed as [light(4)][y(8)][x(26)][z(26)] with x/z biased so neighbour offsets are plain addition
public final class LightingEngine {
    private static final int MAX_LIGHT = 15;

    // Starting capacity of each queue's dedup set, kept small since 34 queues x up to 4 engines means every doubling costs megabytes of empty table before a block is placed
    private static final int QUEUE_CAPACITY = 512;

    // Layout parameters: length of each bit segment.
    private static final int L_X = 26;
    private static final int L_Y = 8;
    private static final int L_Z = 26;
    private static final int L_L = 4;

    // Bit segment shifts.
    private static final int S_Z = 0;
    private static final int S_X = S_Z + L_Z;
    private static final int S_Y = S_X + L_X;
    private static final int S_L = S_Y + L_Y;

    // Bit segment masks.
    private static final long M_X = (1L << L_X) - 1;
    private static final long M_Y = (1L << L_Y) - 1;
    private static final long M_Z = (1L << L_Z) - 1;
    private static final long M_L = (1L << L_L) - 1;
    private static final long M_POS = (M_Y << S_Y) | (M_X << S_X) | (M_Z << S_Z);

    // Set when a neighbour offset carried out of the y field, i.e. stepped outside the world
    private static final long Y_CHECK = 1L << (S_Y + L_Y);

    // Isolates the chunk a position belongs to, so the chunk lookup can be skipped when it repeats
    private static final long M_CHUNK = ((M_X >> 4) << (4 + S_X)) | ((M_Z >> 4) << (4 + S_Z));

    // Encoded offsets for the six neighbours, added directly to an encoded position
    private static final long[] NEIGHBOR_SHIFTS = new long[6];

    static {
        for (int i = 0; i < 6; i++) {
            Vec3i offset = EnumFacing.VALUES[i].getDirectionVec();
            NEIGHBOR_SHIFTS[i] = ((long) offset.getY() << S_Y)
                    | ((long) offset.getX() << S_X)
                    | ((long) offset.getZ() << S_Z);
        }
    }

    private final World world;
    private final Profiler profiler;

    // The thread that constructed the world, kept only to name the offender in lock()'s warning
    private final Thread ownerThread = Thread.currentThread();

    private final ReentrantLock lock = new ReentrantLock();

    // Positions handed to checkLightFor, one queue per light type
    private final DeduplicatedLongQueue[] scheduledUpdates = new DeduplicatedLongQueue[EnumSkyBlock.values().length];

    // Positions to spread from, bucketed by the light level they were set to
    private final DeduplicatedLongQueue[] brighteningQueues = new DeduplicatedLongQueue[MAX_LIGHT + 1];
    // Positions to clear, bucketed by the light level they held
    private final DeduplicatedLongQueue[] darkeningQueues = new DeduplicatedLongQueue[MAX_LIGHT + 1];

    // Scheduled positions found brighter than stored; new level carried in bits 60-63
    private final DeduplicatedLongQueue initialBrightenings;
    // Scheduled positions found darker than stored
    private final DeduplicatedLongQueue initialDarkenings;

    private final int maxScheduledUpdates;
    private final boolean warnOnIllegalThreadAccess;

    // Cursor state; the pass is single-threaded under the lock, so it lives in fields rather than being threaded through every call
    private final MutableBlockPos currentPos = new MutableBlockPos();
    private final NeighborInfo[] neighborInfos = new NeighborInfo[6];

    private DeduplicatedLongQueue currentQueue;
    private Chunk currentChunk;
    private long currentChunkIdentifier;
    private long currentData;

    private boolean isNeighborDataValid;
    private boolean updating;

    // Positions dequeued during the current pass, published to Fulgor when it ends
    private long processedThisPass;

    public LightingEngine(World world) {
        this.world = world;
        this.profiler = world.profiler;

        FulgorConfig config = FulgorConfig.get();

        this.maxScheduledUpdates = config.maxScheduledUpdates;
        this.warnOnIllegalThreadAccess = config.warnOnIllegalThreadAccess;

        boolean deduplicate = config.deduplicateUpdates;
        DeduplicatedLongQueue.Pool pool = new DeduplicatedLongQueue.Pool();

        this.initialBrightenings = new DeduplicatedLongQueue(pool, QUEUE_CAPACITY, deduplicate);
        this.initialDarkenings = new DeduplicatedLongQueue(pool, QUEUE_CAPACITY, deduplicate);

        for (int i = 0; i < this.scheduledUpdates.length; i++) {
            this.scheduledUpdates[i] = new DeduplicatedLongQueue(pool, QUEUE_CAPACITY, deduplicate);
        }

        for (int i = 0; i <= MAX_LIGHT; i++) {
            this.brighteningQueues[i] = new DeduplicatedLongQueue(pool, QUEUE_CAPACITY, deduplicate);
            this.darkeningQueues[i] = new DeduplicatedLongQueue(pool, QUEUE_CAPACITY, deduplicate);
        }

        for (int i = 0; i < this.neighborInfos.length; i++) {
            this.neighborInfos[i] = new NeighborInfo();
        }
    }

    // Records that a position's light may be stale, resolved on next read; Fulgor's whole replacement for World.checkLightFor
    public void scheduleLightUpdate(EnumSkyBlock lightType, BlockPos pos) {
        lock();

        try {
            DeduplicatedLongQueue queue = this.scheduledUpdates[lightType.ordinal()];

            Fulgor.recordScheduled();

            if (!queue.enqueue(encodeWorldCoord(pos))) {
                Fulgor.recordDeduplicated();
                return;
            }

            // Deferral is a memory trade and a producer that never reads light (generator, world-edit) would grow this unbounded, so flush and give up batching for one pass; skipped while a pass is running since re-entry is invalid
            if (!this.updating && queue.size() >= this.maxScheduledUpdates) {
                processLightUpdatesForType(lightType);
            }
        } finally {
            this.lock.unlock();
        }
    }

    // Resolves everything pending, for both light types
    public void processLightUpdates() {
        processLightUpdatesForType(EnumSkyBlock.SKY);
        processLightUpdatesForType(EnumSkyBlock.BLOCK);
    }

    // Resolves everything pending for one light type
    public void processLightUpdatesForType(EnumSkyBlock lightType) {
        // The client reaches this from off-thread callers (chunk builders, sound engine, mod render hooks) that must not mutate the world; unlike the server there is a tick that will do the work shortly, so they are turned away
        if (this.world.isRemote && !isCallingFromMainThread()) {
            return;
        }

        DeduplicatedLongQueue queue = this.scheduledUpdates[lightType.ordinal()];

        // Cheap volatile read before the lock; every light read goes through here and almost none have anything to do
        if (queue.isEmpty()) {
            return;
        }

        lock();

        try {
            processLightUpdatesForTypeInner(lightType, queue);
        } finally {
            this.lock.unlock();
        }
    }

    // Guards the render-thread-only cache reads
    @SideOnly(Side.CLIENT)
    private boolean isCallingFromMainThread() {
        return Minecraft.getMinecraft().isCallingFromMinecraftThread();
    }

    // Contention means another mod is touching the world from the wrong thread; blocking stalls but beats the corruption of proceeding, so warn and block
    private void lock() {
        if (this.lock.tryLock()) {
            return;
        }

        if (this.warnOnIllegalThreadAccess) {
            Thread current = Thread.currentThread();

            if (current != this.ownerThread) {
                IllegalAccessException trace = new IllegalAccessException(String.format(
                        "World is owned by '%s' (ID: %s), but was accessed from thread '%s' (ID: %s)",
                        this.ownerThread.getName(), this.ownerThread.getId(), current.getName(), current.getId()));

                Fulgor.LOGGER.warn("Something (likely another mod) has attempted to modify the world's state from the "
                        + "wrong thread!\nThis is *bad practice* and can cause severe issues in your game. Fulgor has "
                        + "mitigated the violation, but it may introduce stalls.\nPlease report this to the offending "
                        + "mod with the stacktrace below. You can silence this warning by setting "
                        + "`warnOnIllegalThreadAccess` to `false` in config/impetus-fulgor.cfg.", trace);
            }
        }

        this.lock.lock();
    }

    // Runs one full propagation pass; re-entry is a bug, so it throws rather than corrupting the queues
    private void processLightUpdatesForTypeInner(EnumSkyBlock lightType, DeduplicatedLongQueue queue) {
        if (this.updating) {
            throw new IllegalStateException("Already processing light updates");
        }

        this.updating = true;
        this.currentChunkIdentifier = -1;
        this.processedThisPass = 0L;

        try {
            propagate(lightType, queue);
        } finally {
            // The pass reaches foreign code via notifyLightSet and block light values and can throw; leaving the flag set would turn one mod's exception into dead lighting for the session
            Fulgor.recordProcessed(this.processedThisPass);
            this.updating = false;
        }
    }

    // The pass itself: sort scheduled positions into brighten/darken, then settle each level in lockstep
    private void propagate(EnumSkyBlock lightType, DeduplicatedLongQueue queue) {
        this.profiler.startSection("fulgor");
        this.profiler.startSection("sort");

        // Sort scheduled positions into "brighter" and "darker" without acting yet; a position can be reached by both, and scheduling from here would enqueue it once per neighbour that noticed
        beginDraining(queue);

        while (nextItem()) {
            if (this.currentChunk == null) {
                continue;
            }

            int oldLight = getCursorCachedLight(lightType);
            int newLight = calculateNewLightFromCursor(lightType);

            if (oldLight < newLight) {
                this.initialBrightenings.enqueue(((long) newLight << S_L) | this.currentData);
            } else if (oldLight > newLight) {
                this.initialDarkenings.enqueue(this.currentData);
            }
        }

        this.profiler.endStartSection("seed");

        beginDraining(this.initialBrightenings);

        while (nextItem()) {
            int newLight = (int) (this.currentData >> S_L & M_L);

            if (newLight > getCursorCachedLight(lightType)) {
                // Setting the light here as well as queueing is what stops a position being scheduled twice: the second visit sees the new value and stops. M_POS strips the light field off the key
                enqueueBrightening(this.currentPos, this.currentData & M_POS, newLight, this.currentChunk, lightType);
            }
        }

        beginDraining(this.initialDarkenings);

        while (nextItem()) {
            int oldLight = getCursorCachedLight(lightType);

            if (oldLight != 0) {
                enqueueDarkening(this.currentPos, this.currentData, oldLight, this.currentChunk, lightType);
            }
        }

        this.profiler.endStartSection("propagate");

        // Brightest to darkest, darkening then brightening at each level; a position can only be enqueued below the level being processed, so nothing is revisited
        for (int currentLight = MAX_LIGHT; currentLight >= 0; currentLight--) {
            beginDraining(this.darkeningQueues[currentLight]);

            while (nextItem()) {
                // Something else brightened this position after it was queued; nothing to clear.
                if (getCursorCachedLight(lightType) >= currentLight) {
                    continue;
                }

                IBlockState state = LightUtil.posToState(this.currentPos, this.currentChunk);
                int luminosity = getCursorLuminosity(state, lightType);
                int opacity = luminosity >= MAX_LIGHT - 1
                        ? 1 // Irrelevant: nothing this bright can be darkened by its own opacity.
                        : getPosOpacity(this.currentPos, state, this.currentChunk);

                if (calculateNewLightFromCursor(luminosity, opacity, lightType) < currentLight) {
                    // We got darker, so anything we were lighting must be reconsidered, ignoring neighbours about to be darkened themselves or they would prop each other up
                    int newLight = luminosity;

                    fetchNeighborDataFromCursor(lightType);

                    for (NeighborInfo info : this.neighborInfos) {
                        Chunk neighborChunk = info.chunk;

                        if (neighborChunk == null || info.light == 0) {
                            continue;
                        }

                        MutableBlockPos neighborPos = info.pos;
                        IBlockState neighborState = LightUtil.posToState(neighborPos, info.section);

                        if (currentLight - getPosOpacity(neighborPos, neighborState, neighborChunk) >= info.light) {
                            // We could have been its light source, so it has to be re-derived too.
                            enqueueDarkening(neighborPos, info.key, info.light, neighborChunk, lightType);
                        } else {
                            // Brighter than we can account for, so it has an independent source; processing order guarantees nobody darkens it later, so it is safe to be lit by
                            newLight = Math.max(newLight, info.light - opacity);
                        }
                    }

                    enqueueBrighteningFromCursor(newLight, lightType);
                } else {
                    // A false alarm, still as bright as before; the value was zeroed when queued so it must be put back, queued rather than spread so neighbours are not scheduled twice
                    enqueueBrighteningFromCursor(currentLight, lightType);
                }
            }

            beginDraining(this.brighteningQueues[currentLight]);

            while (nextItem()) {
                // Anything but an exact match means the position moved on after being queued, and whatever moved it queued its own follow-up
                if (getCursorCachedLight(lightType) != currentLight) {
                    continue;
                }

                this.world.notifyLightSet(this.currentPos);

                if (currentLight > 1) {
                    spreadLightFromCursor(currentLight, lightType);
                }
            }
        }

        this.profiler.endSection();
        this.profiler.endSection();
    }

    // Points the cursor at a queue and clears its dedup set; safe because every queue is fully filled before it is drained
    private void beginDraining(DeduplicatedLongQueue queue) {
        this.currentQueue = queue;
        queue.resetDeduplication();
    }

    // Advances the cursor; returns whether there was anything left
    private boolean nextItem() {
        if (this.currentQueue.isEmpty()) {
            this.currentQueue = null;
            return false;
        }

        this.currentData = this.currentQueue.dequeue();
        this.isNeighborDataValid = false;

        decodeWorldCoord(this.currentPos, this.currentData);

        long chunkIdentifier = this.currentData & M_CHUNK;

        if (this.currentChunkIdentifier != chunkIdentifier) {
            this.currentChunk = getChunk(this.currentPos);
            this.currentChunkIdentifier = chunkIdentifier;
        }

        this.processedThisPass++;

        return true;
    }

    // Fills neighborInfos for the cursor position if it moved since the last fill; an unloaded neighbour gets a null chunk (skipped by every caller) with its other fields left stale on purpose
    private void fetchNeighborDataFromCursor(EnumSkyBlock lightType) {
        if (this.isNeighborDataValid) {
            return;
        }

        this.isNeighborDataValid = true;

        for (int i = 0; i < this.neighborInfos.length; i++) {
            NeighborInfo info = this.neighborInfos[i];

            long neighborKey = info.key = this.currentData + NEIGHBOR_SHIFTS[i];

            if ((neighborKey & Y_CHECK) != 0) {
                info.chunk = null;
                info.section = null;
                continue;
            }

            MutableBlockPos neighborPos = decodeWorldCoord(info.pos, neighborKey);

            Chunk neighborChunk = (neighborKey & M_CHUNK) == this.currentChunkIdentifier
                    ? this.currentChunk
                    : getChunk(neighborPos);

            info.chunk = neighborChunk;

            if (neighborChunk == null) {
                info.section = null;
                continue;
            }

            ExtendedBlockStorage section = neighborChunk.getBlockStorageArray()[neighborPos.getY() >> 4];

            info.section = section;
            info.light = getCachedLightFor(neighborChunk, section, neighborPos, lightType);
        }
    }

    // The brightest a position could legitimately be, given its own luminosity and its neighbours'
    private int calculateNewLightFromCursor(EnumSkyBlock lightType) {
        IBlockState state = LightUtil.posToState(this.currentPos, this.currentChunk);

        int luminosity = getCursorLuminosity(state, lightType);
        int opacity = luminosity >= MAX_LIGHT - 1
                ? 1
                : getPosOpacity(this.currentPos, state, this.currentChunk);

        return calculateNewLightFromCursor(luminosity, opacity, lightType);
    }

    // Light a block should have given its own emission and the brightest neighbour minus opacity
    private int calculateNewLightFromCursor(int luminosity, int opacity, EnumSkyBlock lightType) {
        // Already at least as bright as anything could make it, so the neighbours cannot matter.
        if (luminosity >= MAX_LIGHT - opacity) {
            return luminosity;
        }

        int newLight = luminosity;

        fetchNeighborDataFromCursor(lightType);

        for (NeighborInfo info : this.neighborInfos) {
            if (info.chunk == null) {
                continue;
            }

            newLight = Math.max(info.light - opacity, newLight);
        }

        return newLight;
    }

    // Queues every neighbour that would get brighter from the cursor's new value
    private void spreadLightFromCursor(int currentLight, EnumSkyBlock lightType) {
        fetchNeighborDataFromCursor(lightType);

        for (NeighborInfo info : this.neighborInfos) {
            Chunk neighborChunk = info.chunk;

            // Opacity is at least 1, so a neighbour at or above our level can never be brightened.
            if (neighborChunk == null || currentLight <= info.light) {
                continue;
            }

            MutableBlockPos neighborPos = info.pos;
            IBlockState neighborState = LightUtil.posToState(neighborPos, info.section);

            int newLight = currentLight - getPosOpacity(neighborPos, neighborState, neighborChunk);

            if (newLight > info.light) {
                enqueueBrightening(neighborPos, info.key, newLight, neighborChunk, lightType);
            }
        }
    }

    // Cursor-relative overload so the hot loop avoids re-fetching chunk and data
    private void enqueueBrighteningFromCursor(int newLight, EnumSkyBlock lightType) {
        enqueueBrightening(this.currentPos, this.currentData, newLight, this.currentChunk, lightType);
    }

    // Queues the position for spreading and writes the new level, so a second visit is a no-op
    private void enqueueBrightening(BlockPos pos, long key, int newLight, Chunk chunk, EnumSkyBlock lightType) {
        this.brighteningQueues[newLight].enqueue(key);

        chunk.setLightFor(lightType, pos, newLight);
    }

    // Queues the position for clearing and zeroes it, so a second visit is a no-op
    private void enqueueDarkening(BlockPos pos, long key, int oldLight, Chunk chunk, EnumSkyBlock lightType) {
        this.darkeningQueues[oldLight].enqueue(key);

        chunk.setLightFor(lightType, pos, 0);
    }

    // Reads the chunk's cached level for the cursor without a world lookup
    private int getCursorCachedLight(EnumSkyBlock lightType) {
        return ((ChunkLightingData) this.currentChunk).fulgor$getCachedLightFor(lightType, this.currentPos);
    }

    // Same read as ChunkLightingData.fulgor$getCachedLightFor for an already-resolved section; fetchNeighborDataFromCursor needs the section anyway
    private int getCachedLightFor(Chunk chunk, ExtendedBlockStorage section, BlockPos pos, EnumSkyBlock type) {
        if (section == Chunk.NULL_BLOCK_STORAGE) {
            return type == EnumSkyBlock.SKY && chunk.canSeeSky(pos) ? type.defaultLightValue : 0;
        }

        int x = pos.getX() & 15;
        int y = pos.getY() & 15;
        int z = pos.getZ() & 15;

        if (type == EnumSkyBlock.SKY) {
            // Asked live rather than cached: WorldProvider.hasSkyLight() is only populated by registerWorld, after the World constructor this engine is built in
            return this.world.provider.hasSkyLight() ? section.getSkyLight(x, y, z) : 0;
        }

        return type == EnumSkyBlock.BLOCK ? section.getBlockLight(x, y, z) : type.defaultLightValue;
    }

    // For skylight, luminosity is a heightmap property: open sky above means a full-strength source, otherwise not a source at all
    private int getCursorLuminosity(IBlockState state, EnumSkyBlock lightType) {
        if (lightType == EnumSkyBlock.SKY) {
            return this.currentChunk.canSeeSky(this.currentPos) ? EnumSkyBlock.SKY.defaultLightValue : 0;
        }

        return MathHelper.clamp(LightUtil.getLightValue(state, this.world, this.currentPos, this.currentChunk),
                0, MAX_LIGHT);
    }

    // Clamped to at least 1: a zero-opacity step would let light travel unattenuated forever
    private int getPosOpacity(BlockPos pos, IBlockState state, Chunk chunk) {
        return MathHelper.clamp(LightUtil.getLightOpacity(state, this.world, pos, chunk), 1, MAX_LIGHT);
    }

    // Loaded-only lookup; null for an unloaded chunk, which callers treat as a hard boundary
    private Chunk getChunk(BlockPos pos) {
        return this.world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    // Unpacks a queue key back to a position, undoing the x/z bias
    private static MutableBlockPos decodeWorldCoord(MutableBlockPos pos, long key) {
        return pos.setPos(
                (int) (key >> S_X & M_X) - (1 << L_X - 1),
                (int) (key >> S_Y & M_Y),
                (int) (key >> S_Z & M_Z) - (1 << L_Z - 1));
    }

    // Packs a position into a queue key; the bias keeps negative x/z from touching the sign bit
    private static long encodeWorldCoord(BlockPos pos) {
        return ((long) pos.getY() << S_Y)
                | ((long) pos.getX() + (1 << L_X - 1) << S_X)
                | ((long) pos.getZ() + (1 << L_Z - 1) << S_Z);
    }

    // Scratch space for one neighbour, reused across the whole pass
    private static final class NeighborInfo {
        final MutableBlockPos pos = new MutableBlockPos();

        Chunk chunk;
        ExtendedBlockStorage section;

        int light;
        long key;
    }
}
