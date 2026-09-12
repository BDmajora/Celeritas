package com.bdmajora.impetus.engine.impl.render.chunk.map;

import it.unimi.dsi.fastutil.longs.*;
import com.bdmajora.impetus.engine.impl.util.PositionUtil;

public class ChunkTracker implements ClientChunkEventListener {
    private final Long2IntOpenHashMap chunkStatus = new Long2IntOpenHashMap();
    private final LongOpenHashSet chunkReady = new LongOpenHashSet();

    private final LongSet unloadQueue = new LongOpenHashSet();
    private final LongSet loadQueue = new LongOpenHashSet();

    private int requiredNeighborRadius;

    public ChunkTracker() {
        this(1);
    }

    // radius 0 is technically valid but produces wrong edge rendering for blocks that read neighbor data (fences, fluids);
    // vanilla papers over this by re-updating chunks as neighbors load, which wastes CPU and still looks bad
    public ChunkTracker(int requiredNeighborRadius) {
        if (requiredNeighborRadius < 0) {
            throw new IllegalArgumentException("requiredNeighborRadius must be nonnegative");
        }
        this.requiredNeighborRadius = requiredNeighborRadius;
    }

    // How many loaded neighbours a chunk needs before it is ready to render
    public void setRequiredNeighborRadius(int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be nonnegative");
        }
        if (this.requiredNeighborRadius == radius) {
            return;
        }
        boolean fullUpdate = radius > this.requiredNeighborRadius;
        if (fullUpdate) {
            // The requirement is now stricter; so we must clear chunkReady
            var readyIterator = this.chunkReady.iterator();
            while (readyIterator.hasNext()) {
                long key = readyIterator.nextLong();
                if (!this.loadQueue.remove(key)) {
                    this.unloadQueue.add(key);
                }
            }
            this.chunkReady.clear();
        }
        this.requiredNeighborRadius = radius;
        // Recompute status of each chunk; this will repopulate chunkReady
        var trackedChunksIterator = this.chunkStatus.keySet().iterator();
        while (trackedChunksIterator.hasNext()) {
            long pos = trackedChunksIterator.nextLong();
            if (!fullUpdate && this.chunkReady.contains(pos)) {
                continue;
            }
            var x = PositionUtil.unpackChunkX(pos);
            var z = PositionUtil.unpackChunkZ(pos);
            this.updateMerged(x, z);
        }
    }

    // Recentres on the player
    @Override
    public void updateMapCenter(int chunkX, int chunkZ) {

    }

    // Resizes the tracked area
    @Override
    public void updateLoadDistance(int loadDistance) {

    }

    // A chunk gained data or light; may make it or neighbours ready
    @Override
    public void onChunkStatusAdded(int x, int z, int flags) {
        var key = PositionUtil.packChunk(x, z);

        var prev = this.chunkStatus.get(key);
        var cur = prev | flags;

        if (prev == cur) {
            return;
        }

        this.chunkStatus.put(key, cur);

        this.updateNeighbors(x, z);
    }

    // A chunk lost data; may make neighbours unready
    @Override
    public void onChunkStatusRemoved(int x, int z, int flags) {
        var key = PositionUtil.packChunk(x, z);

        var prev = this.chunkStatus.get(key);
        int cur = prev & ~flags;

        if (prev == cur) {
            return;
        }

        if (cur == this.chunkStatus.defaultReturnValue()) {
            this.chunkStatus.remove(key);
        } else {
            this.chunkStatus.put(key, cur);
        }

        this.updateNeighbors(x, z);
    }

    // Re-evaluates readiness of every chunk within the radius
    private void updateNeighbors(int x, int z) {
        int r = this.requiredNeighborRadius;
        for (int ox = -r; ox <= r; ox++) {
            for (int oz = -r; oz <= r; oz++) {
                this.updateMerged(ox + x, oz + z);
            }
        }
    }

    // Recomputes one chunk's readiness from its neighbours and queues a load or unload event
    private void updateMerged(int x, int z) {
        long key = PositionUtil.packChunk(x, z);

        int r = this.requiredNeighborRadius;
        int flags = this.chunkStatus.get(key);

        for (int ox = -r; ox <= r; ox++) {
            for (int oz = -r; oz <= r; oz++) {
                flags &= this.chunkStatus.get(PositionUtil.packChunk(ox + x, oz + z));
            }
        }

        if (flags == ChunkStatus.FLAG_ALL) {
            if (this.chunkReady.add(key) && !this.unloadQueue.remove(key)) {
                this.loadQueue.add(key);
            }
        } else {
            if (this.chunkReady.remove(key) && !this.loadQueue.remove(key)) {
                this.unloadQueue.add(key);
            }
        }
    }

    // Every chunk currently ready
    public LongCollection getReadyChunks() {
        return LongSets.unmodifiable(this.chunkReady);
    }

    // Drains the queued events
    public void forEachEvent(ChunkEventHandler loadEventHandler, ChunkEventHandler unloadEventHandler) {
        forEachChunk(this.unloadQueue, unloadEventHandler);
        this.unloadQueue.clear();

        forEachChunk(this.loadQueue, loadEventHandler);
        this.loadQueue.clear();
    }

    // Unpacks each key and calls the handler
    public static void forEachChunk(LongCollection queue, ChunkEventHandler handler) {
        var iterator = queue.iterator();

        while (iterator.hasNext()) {
            var pos = iterator.nextLong();

            var x = PositionUtil.unpackChunkX(pos);
            var z = PositionUtil.unpackChunkZ(pos);

            handler.apply(x, z);
        }
    }

    public interface ChunkEventHandler {
        void apply(int x, int z);
    }
}