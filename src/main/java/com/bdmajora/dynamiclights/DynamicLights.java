package com.bdmajora.dynamiclights;

import com.bdmajora.dynamiclights.client.DynamicLightHandlers;
import com.bdmajora.dynamiclights.client.DynamicLightsEngine;
import com.bdmajora.dynamiclights.client.TileEntityLightTicker;
import com.bdmajora.dynamiclights.client.item.ItemLightSources;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * The Dynamic Lights subsystem: LambDynLights' light-emitting entities and items, ported to Impetus
 * on 1.12.2.
 *
 * <p>A held torch, a dropped glowstone block or a burning creeper lights the world around it. None of
 * that is real block light — the engine tracks the sources itself, folds their contribution into the
 * lightmap at every place light is read, and schedules a chunk rebuild when a source moves far enough
 * to matter. Nothing is written back to the world, so the effect is purely client-side.
 *
 * <p>Sources: <a href="https://github.com/LambdAurora/LambDynamicLights">LambDynLights</a> by way of
 * <a href="https://github.com/Txni/SodiumDynamicLights">SodiumDynamicLights</a> for the engine, and
 * Celeritas Dynamic Lights for most of the 1.12.2 injection points.
 *
 * <p>Like {@code Extras}, none of the mixins here are gated at coremod time: they read
 * {@link #options()} at call time, so the mode switch takes effect the moment it changes.
 */
public final class DynamicLights {
    public static final Logger LOGGER = LogManager.getLogger("Impetus/DynamicLights");

    private static final String FILE_NAME = "impetus-dynamiclights.cfg";

    private static volatile DynamicLightsConfig config;

    private DynamicLights() {
    }

    /**
     * The live options, loading them on first use.
     *
     * <p>Called from mixin bodies on the client, render and chunk-builder threads, so the first call
     * has to be safe from wherever it happens to land. {@link com.bdmajora.impetus.ImpetusVintage}
     * warms it during construction, which in practice is always well before any of those bodies run.
     */
    public static DynamicLightsConfig options() {
        DynamicLightsConfig loaded = config;
        if (loaded == null) {
            synchronized (DynamicLights.class) {
                loaded = config;
                if (loaded == null) {
                    loaded = DynamicLightsConfig.load(configFile());
                    config = loaded;
                }
            }
        }
        return loaded;
    }

    /** The tracked light sources and the lightmap maths over them. */
    public static DynamicLightsEngine engine() {
        return DynamicLightsEngine.get();
    }

    /** Loads the config now rather than on the first mixin that asks for it. */
    public static void initialize() {
        options();
    }

    /**
     * Registers the default light handlers and the item light source reload listener.
     *
     * <p>Deliberately not done alongside {@link #initialize()} during construction.
     * {@code SimpleReloadableResourceManager#registerReloadListener} invokes the listener
     * <em>immediately</em>, and at construction time Forge's registry events have not fired yet — so
     * every {@code ForgeRegistries.ITEMS} lookup would come back null, every definition would be
     * dropped as "item not installed", and a held torch would silently fail to light anything.
     * Initialization is the first point where the item registry is populated.
     */
    public static void onClientInit() {
        DynamicLightHandlers.registerDefaultHandlers();
        ItemLightSources.registerReloadListener();
        MinecraftForge.EVENT_BUS.register(TileEntityLightTicker.instance());
    }

    /** Persists the current options. Safe to call before {@link #initialize()}. */
    public static void save() {
        options().writeChanges();
    }

    private static File configFile() {
        File home = Launch.minecraftHome;
        File directory = new File(home == null ? new File(".") : home, "config");

        if (!directory.isDirectory() && !directory.mkdirs()) {
            LOGGER.warn("Could not create {}, Dynamic Lights settings will not persist", directory);
        }

        return new File(directory, FILE_NAME);
    }
}
