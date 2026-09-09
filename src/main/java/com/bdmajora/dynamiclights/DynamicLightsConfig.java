package com.bdmajora.dynamiclights;

import com.bdmajora.dynamiclights.client.LightSourceSettings;
import com.github.bsideup.jabel.Desugar;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

// every setting the Dynamic Lights page owns, persisted to config/impetus-dynamiclights.cfg
// built the same way as ExtrasConfig: booleans go in a declarative table so load and save cannot
// drift apart, and enums are stored by ordinal
// enum constant order is therefore part of the on-disk format - appending is safe, reordering
// silently changes what a saved config means
// the per-type light source toggles are not fields here; they live in LightSourceSettings, which this
// class only persists
public final class DynamicLightsConfig {
    private static final String CAT_GENERAL = "general";
    private static final String CAT_SOURCES = "light_sources";

    public DynamicLightsMode mode = DynamicLightsMode.REALTIME;
    public ExplosiveLightingMode creeperLighting = ExplosiveLightingMode.FANCY;
    public ExplosiveLightingMode tntLighting = ExplosiveLightingMode.FANCY;

    public boolean selfLightSource = true;
    public boolean entitiesLightSource = true;
    public boolean blockEntitiesLightSource = true;
    public boolean waterSensitiveCheck = true;
    public boolean showDebugInfo = false;

    private final List<BooleanProperty> booleans = Arrays.asList(
            bool(CAT_GENERAL, "selfLightSource", true,
                    "Light the world from what the player themselves is holding or wearing",
                    v -> selfLightSource = v, () -> selfLightSource),
            bool(CAT_GENERAL, "entitiesLightSource", true,
                    "Light the world from other entities: mobs, dropped items, item frames",
                    v -> entitiesLightSource = v, () -> entitiesLightSource),
            bool(CAT_GENERAL, "blockEntitiesLightSource", true,
                    "Light the world from block entities that register a handler through the API",
                    v -> blockEntitiesLightSource = v, () -> blockEntitiesLightSource),
            bool(CAT_GENERAL, "waterSensitiveCheck", true,
                    "Extinguish water-sensitive sources (torches, lava buckets) while submerged",
                    v -> waterSensitiveCheck = v, () -> waterSensitiveCheck),
            bool(CAT_GENERAL, "showDebugInfo", false,
                    "Add a tracked-source count line to the F3 overlay",
                    v -> showDebugInfo = v, () -> showDebugInfo)
    );

    private Configuration config;

    // reads the file, adding any keys it does not yet contain
    // a read failure yields a fresh defaults instance that is deliberately *not* written back:
    // overwriting a config we failed to parse would destroy the user's settings along with whatever
    // confused us
    public static DynamicLightsConfig load(File file) {
        DynamicLightsConfig options = new DynamicLightsConfig();
        Configuration config = new Configuration(file);

        try {
            config.load();
            options.config = config;
            options.loadFrom(config);
            if (config.hasChanged()) {
                config.save();
            }
            return options;
        } catch (Exception e) {
            DynamicLights.LOGGER.error("Could not read {}, falling back to defaults", file, e);
            DynamicLightsConfig defaults = new DynamicLightsConfig();
            defaults.config = config;
            return defaults;
        }
    }

    private void loadFrom(Configuration config) {
        booleans.forEach(property -> property.load(config));

        mode = readEnum(config, CAT_GENERAL, "mode", DynamicLightsMode.values(), DynamicLightsMode.REALTIME,
                "Update rate (0 = Off, 1 = Slow, 2 = Fast, 3 = Realtime)");
        creeperLighting = readEnum(config, CAT_GENERAL, "creeperLighting", ExplosiveLightingMode.values(),
                ExplosiveLightingMode.FANCY, "Creeper flash lighting (0 = Off, 1 = Simple, 2 = Fancy)");
        tntLighting = readEnum(config, CAT_GENERAL, "tntLighting", ExplosiveLightingMode.values(),
                ExplosiveLightingMode.FANCY, "Primed TNT lighting (0 = Off, 1 = Simple, 2 = Fancy)");

        LightSourceSettings settings = LightSourceSettings.getInstance();
        settings.loadDisabledEntities(config.getStringList("disabledEntities", CAT_SOURCES,
                new String[0], "Entity types the user has switched off"));
        settings.loadDisabledBlockEntities(config.getStringList("disabledBlockEntities", CAT_SOURCES,
                new String[0], "Block entity types the user has switched off"));
    }

    // Flushes every setting back to disk.
    public void writeChanges() {
        if (config == null) {
            return;
        }

        booleans.forEach(property -> property.save(config));

        config.get(CAT_GENERAL, "mode", DynamicLightsMode.REALTIME.ordinal()).set(mode.ordinal());
        config.get(CAT_GENERAL, "creeperLighting", ExplosiveLightingMode.FANCY.ordinal())
                .set(creeperLighting.ordinal());
        config.get(CAT_GENERAL, "tntLighting", ExplosiveLightingMode.FANCY.ordinal())
                .set(tntLighting.ordinal());

        LightSourceSettings settings = LightSourceSettings.getInstance();
        config.get(CAT_SOURCES, "disabledEntities", new String[0]).set(settings.getDisabledEntitiesArray());
        config.get(CAT_SOURCES, "disabledBlockEntities", new String[0])
                .set(settings.getDisabledBlockEntitiesArray());

        config.save();
    }

    private static <T extends Enum<T>> T readEnum(Configuration config, String category, String key,
                                                  T[] values, T fallback, String comment) {
        int ordinal = config.getInt(key, category, fallback.ordinal(), 0, values.length - 1, comment);
        return values[ordinal];
    }

    private BooleanProperty bool(String category, String key, boolean defaultValue, String comment,
                                 Consumer<Boolean> setter, Supplier<Boolean> getter) {
        return new BooleanProperty(category, key, defaultValue, comment, setter, getter);
    }

    // Something with a lang key; lets the option page build cycling controls generically.
    public interface Localized {
        String translationKey();

        default String localizedName() {
            return I18n.format(this.translationKey());
        }
    }

    // A boolean config entry bound to its in-memory field, so load and save cannot drift apart.
    @Desugar
    private record BooleanProperty(String category, String key, boolean defaultValue, String comment,
                                   Consumer<Boolean> setter, Supplier<Boolean> getter) {
        void load(Configuration config) {
            setter.accept(config.getBoolean(key, category, defaultValue, comment));
        }

        void save(Configuration config) {
            config.get(category, key, defaultValue).set(getter.get());
        }
    }
}
