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

// the Dynamic Lights subsystem: LambDynLights' light-emitting entities and items, ported to Impetus on
// 1.12.2
// a held torch, a dropped glowstone block or a burning creeper lights the world around it, but none of
// that is real block light - the engine tracks the sources itself, folds their contribution into the
// lightmap at every place light is read, and schedules a chunk rebuild when a source moves far enough
// to matter
// nothing is written back to the world, so the effect is purely client-side
// sources: LambDynLights (https://github.com/LambdAurora/LambDynamicLights) by way of
// SodiumDynamicLights (https://github.com/Txni/SodiumDynamicLights) for the engine, and Celeritas
// Dynamic Lights for most of the 1.12.2 injection points
// like Extras, none of the mixins here are gated at coremod time: they read options() at call time, so
// the mode switch takes effect the moment it changes
public final class DynamicLights {
    public static final Logger LOGGER = LogManager.getLogger("Impetus/DynamicLights");

    private static final String FILE_NAME = "impetus-dynamiclights.cfg";

    private static volatile DynamicLightsConfig config;

    private DynamicLights() {
    }

    // the live options, loading them on first use
    // called from mixin bodies on the client, render and chunk-builder threads, so the first call has
    // to be safe from wherever it happens to land
    // ImpetusVintage warms it during construction, which in practice is always well before any of
    // those bodies run
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

    // The tracked light sources and the lightmap maths over them.
    public static DynamicLightsEngine engine() {
        return DynamicLightsEngine.get();
    }

    // Loads the config now rather than on the first mixin that asks for it.
    public static void initialize() {
        options();
    }

    // registers the default light handlers and the item light source reload listener
    // deliberately not done alongside initialize() during construction:
    // SimpleReloadableResourceManager#registerReloadListener invokes the listener *immediately*, and
    // at construction time Forge's registry events have not fired yet - so every ForgeRegistries.ITEMS
    // lookup would come back null, every definition would be dropped as "item not installed", and a
    // held torch would silently fail to light anything
    // client init is the first point where the item registry is populated
    public static void onClientInit() {
        DynamicLightHandlers.registerDefaultHandlers();
        ItemLightSources.registerReloadListener();
        MinecraftForge.EVENT_BUS.register(TileEntityLightTicker.instance());
    }

    // Persists the current options. Safe to call before #initialize().
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
