package com.bdmajora.impetus.engine.impl.render.chunk.sorting.trigger;

import com.bdmajora.impetus.engine.impl.render.chunk.RenderSection;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import java.util.function.Consumer;

// Global face-normal index for precise translucency re-sort scheduling: sections register their quad planes by
// quantised normal, and camera movement is treated as a segment that triggers only the sections whose plane it crossed
// Render thread only
public final class TranslucencyTriggerIndex {
    // Movement components smaller than this can't meaningfully cross a plane; skips whole normal groups.
    private static final double MOVEMENT_EPSILON = 1.0E-9;
    // Widens the crossing window to absorb quantization/float error; in blocks.
    private static final double PLANE_EPSILON = 1.0E-3;

    private final Int2ObjectOpenHashMap<Bucket> buckets = new Int2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<RenderSection, int[]> keysBySection = new Reference2ObjectOpenHashMap<>();

    private static final class Bucket {
        final float nx, ny, nz;
        final ObjectArrayList<Entry> entries = new ObjectArrayList<>();

        Bucket(float nx, float ny, float nz) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
    }

    // A section and its per-normal plane distances
    private record Entry(RenderSection section, float[] distances) {
    }

    // registers (or refreshes) the watched planes for a section; an empty array clears it
    public void update(RenderSection section, NormalPlanes[] planes) {
        this.remove(section);

        if (planes.length == 0) {
            return;
        }

        var keys = new int[planes.length];

        for (int i = 0; i < planes.length; i++) {
            var group = planes[i];
            var key = NormalPlanes.quantize(group.nx(), group.ny(), group.nz());
            var bucket = this.buckets.get(key);

            if (bucket == null) {
                bucket = new Bucket(group.nx(), group.ny(), group.nz());
                this.buckets.put(key, bucket);
            }

            bucket.entries.add(new Entry(section, group.distances()));
            keys[i] = key;
        }

        this.keysBySection.put(section, keys);
    }

    // Drops a section from every normal bucket
    public void remove(RenderSection section) {
        var keys = this.keysBySection.remove(section);

        if (keys == null) {
            return;
        }

        for (var key : keys) {
            var bucket = this.buckets.get(key);

            if (bucket == null) {
                continue;
            }

            var entries = bucket.entries;

            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).section == section) {
                    // Swap-remove; a section registers a given normal key at most once, but stay robust.
                    entries.set(i, entries.get(entries.size() - 1));
                    entries.remove(entries.size() - 1);
                }
            }

            if (entries.isEmpty()) {
                this.buckets.remove(key);
            }
        }
    }

    // reports every registered section whose planes the camera segment (x0,y0,z0) -> (x1,y1,z1) crossed
    // a section may be reported more than once if several of its normal groups were crossed
    public void collectTriggered(double x0, double y0, double z0, double x1, double y1, double z1, Consumer<RenderSection> consumer) {
        var dx = x1 - x0;
        var dy = y1 - y0;
        var dz = z1 - z0;

        for (var bucket : this.buckets.values()) {
            // The change in plane-offset is independent of each section's origin, so movement orthogonal to this
            // normal group rules out the whole group at once.
            var delta = bucket.nx * dx + bucket.ny * dy + bucket.nz * dz;

            if (Math.abs(delta) < MOVEMENT_EPSILON) {
                continue;
            }

            for (var entry : bucket.entries) {
                var section = entry.section;
                double ox = section.getOriginX();
                double oy = section.getOriginY();
                double oz = section.getOriginZ();

                // Camera plane-offsets in the section's local space, where the entry's distances live.
                var d1 = bucket.nx * (x1 - ox) + bucket.ny * (y1 - oy) + bucket.nz * (z1 - oz);
                var d0 = d1 - delta;

                var lo = Math.min(d0, d1) - PLANE_EPSILON;
                var hi = Math.max(d0, d1) + PLANE_EPSILON;

                var distances = entry.distances;

                if (hi < distances[0] || lo > distances[distances.length - 1]) {
                    continue;
                }

                var idx = firstAtLeast(distances, lo);

                if (idx < distances.length && distances[idx] <= hi) {
                    consumer.accept(section);
                }
            }
        }
    }

    // Every indexed section
    public void forEachSection(Consumer<RenderSection> consumer) {
        this.keysBySection.keySet().forEach(consumer);
    }

    // Empties the index
    public void clear() {
        this.buckets.clear();
        this.keysBySection.clear();
    }

    // the index of the first element not less than value, or array.length
    private static int firstAtLeast(float[] array, double value) {
        int low = 0;
        int high = array.length;

        while (low < high) {
            int mid = (low + high) >>> 1;

            if (array[mid] < value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }

        return low;
    }
}
