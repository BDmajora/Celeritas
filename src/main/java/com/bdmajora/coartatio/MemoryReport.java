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

// Turns Coartatio's counters into bytes
// Every other statistic in this mod is a hit count, which answers "is it working" but not "was it worth it".
// This converts them into a number in megabytes
//
// Two figures are MEASURED, by summing the actual arrays before they are released: texture pixel data and the
// class loader cache. Those are exact
// The rest are ESTIMATES — a shared-object count times a per-object size. Every size below is for a 64-bit JVM
// with compressed oops (the default under 4 GB of heap, which is every 1.12.2 instance) and is deliberately
// rounded DOWN, because an estimate that flatters the mod is worse than no estimate at all. The report labels
// the two kinds differently and never presents an estimate as a measurement
//
// What this cannot do is measure the heap before and after within one run, since "before" would mean launching
// without the mod. Comparing Runtime.totalMemory across two launches is the honest way, and the command prints
// the live heap so those numbers are to hand
public final class MemoryReport {
    // ---- object sizes, 64-bit JVM with compressed oops ----

    // String: 16 header + 4 value ref + 4 hash, plus its char[] at 16 header + 2 bytes per char
    // Assumes a conservative 12-character average, so a long resource path is undercounted rather than over
    private static final long STRING_BYTES = 24 + 16 + 24;

    // int[28], one baked quad's vertex data: 16 header + 112 payload
    private static final long QUAD_ARRAY_BYTES = 128;

    // ItemCameraTransforms plus its eight ItemTransformVec3f, each holding three Vector3f
    // Rounded well down from the true figure of roughly 600
    private static final long CAMERA_TRANSFORMS_BYTES = 400;

    // A flattened multipart predicate plus its two arrays
    private static final long PREDICATE_BYTES = 64;

    // The DIFFERENCE, not the size: Guava's RegularImmutableMap for a four-entry state is about 240 bytes and
    // CoartatioPropertyMap is about 72, so each compacted map saves the gap
    private static final long PROPERTY_MAP_SAVED_BYTES = 168;

    // Per state, the ImmutableTable that packing removes outright
    // Deliberately low: a block with several multi-valued properties is worth several times this
    private static final long STATE_TABLE_BYTES = 200;

    // Exact totals, filled in by the code that actually does the releasing
    // Atomic because the sprite and class loader figures are accumulated off the main thread
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

    // One line per feature plus a total, shared by the log dump and the /coartatio chat command
    // Features that saved nothing are skipped rather than printed as zero, so the report stays short on an
    // instance where most of the switches are off
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

    // The same arithmetic as lines(), collapsed to one number for the F3 readout
    // Kept separate rather than summing lines() so the overlay does not build a list of strings every frame
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

    // Formats a byte count for display, dropping to KB below a megabyte so a small saving does not read as
    // "0.0 MB"
    public static String mib(long bytes) {
        return bytes >= 1024L * 1024L
                ? String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                : String.format("%.0f KB", bytes / 1024.0);
    }

    // max(0, count) guards the case where a pool was never opened and its counter reads negative or garbage;
    // an impossible count should show as nothing saved, not as a negative subtracted from the total
    private static Line estimated(String name, long count, long each) {
        return new Line(name, Math.max(0, count) * each, false);
    }

    private static Line measured(String name, long bytes) {
        return new Line(name, bytes, true);
    }

    // One report row; exact is what decides whether it prints as "measured" or "estimated"
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

    // The single formatted total the F3 overlay shows
    public static String summary() {
        return mib(totalBytes());
    }
}
