package com.bdmajora.coartatio;

import com.bdmajora.coartatio.dedup.ModelCaches;
import com.bdmajora.coartatio.dedup.ResourceLocationCaches;
import com.bdmajora.coartatio.dedup.StringPool;
import com.bdmajora.coartatio.dedup.TransformCaches;
import com.bdmajora.coartatio.state.CompactPropertyMaps;
import com.bdmajora.coartatio.state.ConditionCanonicalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns Coartatio's counters into bytes.
 *
 * <p>Every other statistic in this mod is a hit count, which answers "is it working" but not "was it
 * worth it". This converts them, so the answer is a number in megabytes.
 *
 * <h2>Exact versus estimated</h2>
 *
 * <p>Two figures are <b>measured</b>, by summing the actual arrays before they are released:
 * texture pixel data and the class loader cache. Those are exact.
 *
 * <p>The rest are <b>estimates</b>: a shared-object count multiplied by a per-object size. The sizes
 * below are for a 64-bit JVM with compressed oops — the default under 4 GB of heap, which is every
 * 1.12.2 instance — and they are deliberately rounded <i>down</i>. An estimate that flatters the mod
 * is worse than no estimate at all, so where a value is uncertain this takes the low end. The report
 * labels the two kinds differently and never presents an estimate as a measurement.
 *
 * <p>What this cannot do is measure the heap before and after in one run, because "before" would mean
 * launching without the mod. Comparing {@code Runtime.totalMemory} across two launches is the honest
 * way to do that, and the command prints the live heap so those numbers are to hand.
 */
public final class MemoryReport {
    // ---- object sizes, 64-bit JVM with compressed oops ----

    /** {@code String}: 16 header + 4 {@code value} ref + 4 {@code hash}, plus a {@code char[]}
     *  (16 header + 2 bytes per char). Assumes a conservative 12-character average. */
    private static final long STRING_BYTES = 24 + 16 + 24;

    /** {@code int[28]} — one baked quad's vertex data: 16 header + 112 payload. */
    private static final long QUAD_ARRAY_BYTES = 128;

    /** {@code ItemCameraTransforms} plus its eight {@code ItemTransformVec3f}, each holding three
     *  {@code Vector3f}. Rounded well down from the true ~600. */
    private static final long CAMERA_TRANSFORMS_BYTES = 400;

    /** A flattened multipart predicate plus its two arrays. */
    private static final long PREDICATE_BYTES = 64;

    /** Guava's {@code RegularImmutableMap} for a four-entry state (~240) against ours (~72). */
    private static final long PROPERTY_MAP_SAVED_BYTES = 168;

    /** Per state: the {@code ImmutableTable} that packing removes outright. Deliberately low —
     *  a block with several multi-valued properties is worth several times this. */
    private static final long STATE_TABLE_BYTES = 200;

    /** Exact totals, filled in by the code that does the releasing. */
    private static final AtomicLong SPRITE_BYTES = new AtomicLong();
    private static final AtomicLong CLASS_LOADER_BYTES = new AtomicLong();
    private static final AtomicLong PACKED_STATE_COUNT = new AtomicLong();

    private MemoryReport() {
    }

    public static void recordSpriteBytes(long bytes) {
        SPRITE_BYTES.addAndGet(bytes);
    }

    public static void recordClassLoaderBytes(long bytes) {
        CLASS_LOADER_BYTES.addAndGet(bytes);
    }

    public static void recordPackedStates(long states) {
        PACKED_STATE_COUNT.addAndGet(states);
    }

    /** A single line per feature, with a total. Shared by the log dump and the chat command. */
    public static List<String> lines() {
        List<Line> entries = new ArrayList<>();

        entries.add(estimated("Resource names",
                (long) ResourceLocationCaches.DOMAINS.shared() + ResourceLocationCaches.PATHS.shared(),
                STRING_BYTES));
        entries.add(estimated("Model variants", ModelCaches.VARIANTS.shared(), STRING_BYTES));
        entries.add(estimated("NBT keys", StringPool.NBT_KEYS.shared(), STRING_BYTES));
        entries.add(estimated("Quad vertex data", ModelCaches.QUADS.shared(), QUAD_ARRAY_BYTES));
        entries.add(estimated("Model transforms", TransformCaches.TRANSFORMS.shared(), CAMERA_TRANSFORMS_BYTES));
        entries.add(estimated("Multipart predicates", ConditionCanonicalizer.sharedCount(), PREDICATE_BYTES));
        entries.add(estimated("Block state tables", PACKED_STATE_COUNT.get(), STATE_TABLE_BYTES));
        entries.add(estimated("State property maps", CompactPropertyMaps.compacted(), PROPERTY_MAP_SAVED_BYTES));
        entries.add(measured("Texture pixel data", SPRITE_BYTES.get()));
        entries.add(measured("Class loader cache", CLASS_LOADER_BYTES.get()));

        long total = 0;
        List<String> out = new ArrayList<>();
        out.add("Coartatio memory saved");

        for (Line entry : entries) {
            if (entry.bytes <= 0) {
                continue;
            }
            total += entry.bytes;
            out.add(String.format("  %-22s %8s  %s", entry.name, mib(entry.bytes), entry.exact ? "measured" : "estimated"));
        }

        out.add(String.format("  %-22s %8s", "TOTAL", mib(total)));

        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        out.add(String.format("  %-22s %8s used of %s allocated, %s max",
                "Heap now", mib(used), mib(runtime.totalMemory()), mib(runtime.maxMemory())));

        return out;
    }

    /** Total saving in bytes, for the one-line F3 readout. */
    public static long totalBytes() {
        return (long) (ResourceLocationCaches.DOMAINS.shared() + ResourceLocationCaches.PATHS.shared()
                + ModelCaches.VARIANTS.shared() + StringPool.NBT_KEYS.shared()) * STRING_BYTES
                + ModelCaches.QUADS.shared() * QUAD_ARRAY_BYTES
                + TransformCaches.TRANSFORMS.shared() * CAMERA_TRANSFORMS_BYTES
                + ConditionCanonicalizer.sharedCount() * PREDICATE_BYTES
                + PACKED_STATE_COUNT.get() * STATE_TABLE_BYTES
                + CompactPropertyMaps.compacted() * PROPERTY_MAP_SAVED_BYTES
                + SPRITE_BYTES.get()
                + CLASS_LOADER_BYTES.get();
    }

    public static String mib(long bytes) {
        return bytes >= 1024L * 1024L
                ? String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                : String.format("%.0f KB", bytes / 1024.0);
    }

    private static Line estimated(String name, long count, long each) {
        return new Line(name, Math.max(0, count) * each, false);
    }

    private static Line measured(String name, long bytes) {
        return new Line(name, bytes, true);
    }

    private static final class Line {
        final String name;
        final long bytes;
        final boolean exact;

        Line(String name, long bytes, boolean exact) {
            this.name = name;
            this.bytes = bytes;
            this.exact = exact;
        }
    }

    /** One-line total, for the F3 overlay. */
    public static String summary() {
        return mib(totalBytes());
    }
}
