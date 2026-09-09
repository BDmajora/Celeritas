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

// Reclaims the caches LaunchWrapper and FML hold alive for the whole session
// The largest single saving in Coartatio, and nothing in Hydrogen corresponds to it: Hydrogen was a Fabric mod,
// where neither LaunchWrapper nor FML's remapper exists
//
// LaunchClassLoader.resourceCache is a Map<String, byte[]> holding the raw bytecode of EVERY class it has ever
// loaded, kept forever so a transformer can ask for a class's bytes later. On a large 1.12.2 pack that is on
// the order of 100-300 MB of live heap doing nothing at all after startup
// There are two established treatments: FoamFix swaps the map for one with weak values so the GC can reclaim
// entries under pressure while a late transformer request still usually hits, and LoliASM clears it outright
// Weakening is what is implemented below, because it keeps the cache functional — coremods and
// ModIdentifier-style crash reporters do read it after load
//
// What is deliberately NOT done here: an earlier version also cleared FMLDeobfuscatingRemapper's SRG tables, on
// the assumption that nothing reads them once mod loading finishes. That is wrong and it crashes. Minecraft
// classes are loaded LAZILY FOR THE ENTIRE SESSION and each is renamed from notch to SRG names as it loads, so
// clearing the tables leaves every class loaded afterwards unmapped — in practice NarratorChatListener loaded
// without its INSTANCE field and GuiIngame died with a NoSuchFieldError moments later
// There is no safe point at which to clear them. LoliASM's optimizeFMLRemapper, which that was modelled on,
// clears nothing: it swaps the tables for a more compact representation and keeps them fully functional, which
// is a different and much larger piece of work
//
// Everything here is reflective and every step fails soft — a changed field name costs one warning line and the
// memory that would have been saved, never a crash. That matters more than usual because this reaches into the
// launcher rather than into the game
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
