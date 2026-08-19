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

/**
 * Reclaims the caches LaunchWrapper and FML keep alive for the whole session.
 *
 * <p>This is the largest single saving in Coartatio, and nothing in Hydrogen corresponds to it —
 * Hydrogen was a Fabric mod, and neither LaunchWrapper nor FML's remapper exists there.
 *
 * <h2>The resource cache</h2>
 *
 * <p>{@code LaunchClassLoader.resourceCache} is a {@code Map<String, byte[]>} holding the raw
 * bytecode of <b>every class it has ever loaded</b>, retained forever so that transformers can ask
 * for a class's bytes later. In a large 1.12.2 pack that is on the order of 100–300 MB of live heap
 * doing nothing after startup.
 *
 * <p>Two established treatments: FoamFix swaps the map for one with weak values, so the GC can
 * reclaim entries under pressure while a late transformer request still usually hits; LoliASM clears
 * it outright. Weakening is the one implemented here — it keeps the cache functional, which matters
 * because coremods and {@code ModIdentifier}-style crash reporters do read it after load.
 *
 * <h2>What is deliberately not done here</h2>
 *
 * <p>An earlier version also cleared {@code FMLDeobfuscatingRemapper}'s SRG tables, on the
 * assumption that nothing reads them once mod loading finishes. That is wrong, and it crashes:
 * Minecraft classes are loaded <i>lazily for the entire session</i>, and each one is renamed from
 * notch to SRG names as it loads. Clearing the tables leaves every class loaded afterwards
 * unmapped — in practice {@code NarratorChatListener} loaded without its {@code INSTANCE} field and
 * {@code GuiIngame} died with {@code NoSuchFieldError} moments later.
 *
 * <p>There is no safe point to clear them. LoliASM's {@code optimizeFMLRemapper}, which this was
 * modelled on, does not clear anything — it swaps the tables for a more compact representation and
 * keeps them fully functional. That is a different and larger piece of work.
 *
 * <p>Everything here is reflective and every step fails soft: a changed field name costs one warning
 * line and the memory we would have saved, never a crash. That matters more than usual because this
 * code reaches into the launcher rather than the game.
 */
public final class ClassLoaderCleaner {
    private static boolean done;

    private ClassLoaderCleaner() {
    }

    /**
     * Runs once, as late as is safe — after mod construction, when every class a coremod will ask
     * for has already been transformed.
     */
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
            Coartatio.LOGGER.info("Weakened LaunchWrapper resource cache ({} entries, {} now collectable)",
                    size, MemoryReport.mib(bytes));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Coartatio.LOGGER.warn("Could not weaken the LaunchWrapper resource cache", e);
        }
    }

    /** Unused today, kept because the negative cache is the other half of the same story. */
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
