package com.bdmajora.coartatio.launch;

import com.bdmajora.coartatio.Coartatio;
import com.bdmajora.coartatio.CoartatioConfig;
import com.bdmajora.coartatio.MemoryReport;
import com.google.common.cache.CacheBuilder;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

// Reclaims the caches LaunchWrapper and FML hold alive all session, the largest single saving in Coartatio
// LaunchClassLoader.resourceCache keeps the raw bytes of every class ever loaded, 100-300 MB on a large pack
// Weakened rather than cleared, as FoamFix does, so late transformer and crash-reporter reads still usually hit
public final class ClassLoaderCleaner {
    // Latched so a second call is a no-op; the entry point is reachable from more than one load phase
    private static boolean done;

    private ClassLoaderCleaner() {
    }

    // Runs once, as late as is safe: after mod construction, by which point every class a coremod is going to
    // ask for has already been transformed
    public static void run() {
        if (done) {
            return;
        }
        done = true;

        CoartatioConfig config = CoartatioConfig.get();

        if (config.weakenClassLoaderCache) {
            weakenResourceCache();
        }
    }

    // Swaps LaunchClassLoader's byte[] cache for a weak-valued map via reflection; failure leaves vanilla behaviour
    @SuppressWarnings("unchecked")
    private static void weakenResourceCache() {
        LaunchClassLoader loader = Launch.classLoader;

        if (loader == null) {
            return;
        }

        try {
            Field field = LaunchClassLoader.class.getDeclaredField("resourceCache");
            field.setAccessible(true);

            Map<String, byte[]> existing = (Map<String, byte[]>) field.get(loader);

            if (existing == null) {
                return;
            }

            int size = existing.size();
            long bytes = 0;

            for (byte[] value : existing.values()) {
                if (value != null) {
                    bytes += 16L + value.length;
                }
            }

            // Weak values rather than clear: coremods and crash-report mod identifiers still read
            // this after load, and a weak map keeps serving them while letting the GC reclaim the
            // bulk of it under pressure.
            Map<String, byte[]> weak = CacheBuilder.newBuilder().weakValues().<String, byte[]>build().asMap();
            weak.putAll(existing);
            field.set(loader, weak);

            MemoryReport.recordClassLoaderBytes(bytes);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Coartatio.LOGGER.warn("Could not weaken the LaunchWrapper resource cache", e);
        }
    }

    // Size of LaunchWrapper's negative resource cache, the set of names it has already failed to find
    // Unused today and kept only because that cache is the other half of the same story; returns -1 when the
    // field cannot be reached, which callers treat as "unknown" rather than "empty"
    @SuppressWarnings("unchecked")
    static int negativeResourceCacheSize() {
        try {
            Field field = LaunchClassLoader.class.getDeclaredField("negativeResourceCache");
            field.setAccessible(true);
            return ((Set<String>) field.get(Launch.classLoader)).size();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }
}
