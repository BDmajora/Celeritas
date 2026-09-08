package com.bdmajora.dynamiclights.gui;

import com.bdmajora.dynamiclights.DynamicLights;
import com.bdmajora.dynamiclights.DynamicLightsConfig;
import com.bdmajora.dynamiclights.DynamicLightsMode;
import com.bdmajora.dynamiclights.ExplosiveLightingMode;
import com.bdmajora.dynamiclights.client.LightSourceSettings;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.control.CyclingControl;
import com.bdmajora.impetus.api.options.control.TickBoxControl;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.options.structure.OptionImpact;
import com.bdmajora.impetus.api.options.structure.OptionImpl;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The Dynamic Lights page.
 *
 * <p>Upstream ships the master switches on one screen and the per-type toggles behind a button that
 * opens a second one. Everything is groups on a single page here, matching how the Extras tab handles
 * its per-particle-class toggles, because the options screen's search bar makes a long page navigable
 * in a way a nested screen is not.
 *
 * <p>Sub-options are gated with {@code setEnabledPredicate} rather than hidden, so switching the mode
 * to Off greys out what it governs instead of making controls appear and disappear as the page is
 * used.
 */
public final class DynamicLightsOptionPages {
    private static final String MOD_ID = "impetus";
    private static final String LANG = "impetus.options.dynamiclights.";

    private static final DynamicLightsOptionsStorage STORAGE = new DynamicLightsOptionsStorage();

    private DynamicLightsOptionPages() {
    }

    public static OptionPage dynamicLights() {
        List<OptionGroup> groups = new ArrayList<>();

        // Built before the groups so every sub-option can gate on the pending value rather than the
        // saved one: flipping the mode to Off greys the rest of the page out immediately, without
        // waiting for Apply.
        OptionImpl<DynamicLightsConfig, DynamicLightsMode> mode = mode();
        BooleanSupplier enabled = () -> mode.getValue().isEnabled();

        groups.add(general(mode, enabled));
        groups.add(explosives(enabled));
        addTypeGroups(groups, enabled);

        return new OptionPage(
                OptionIdentifier.create(MOD_ID, "dynamiclights"),
                TextComponent.translatable("impetus.options.pages.dynamiclights"),
                ImmutableList.copyOf(groups));
    }

    // ------------------------------------------------------------------------------------------
    // Groups
    // ------------------------------------------------------------------------------------------

    private static OptionGroup general(OptionImpl<DynamicLightsConfig, DynamicLightsMode> mode,
                                       BooleanSupplier enabled) {
        return OptionGroup.createBuilder()
                .setId(group("general"))
                .add(mode)
                .add(toggle("self", enabled,
                        (config, value) -> config.selfLightSource = value,
                        config -> config.selfLightSource))
                .add(toggle("entities", enabled,
                        (config, value) -> config.entitiesLightSource = value,
                        config -> config.entitiesLightSource))
                .add(toggle("block_entities", enabled,
                        (config, value) -> config.blockEntitiesLightSource = value,
                        config -> config.blockEntitiesLightSource))
                .add(toggle("water_sensitive", enabled,
                        (config, value) -> config.waterSensitiveCheck = value,
                        config -> config.waterSensitiveCheck))
                .add(toggle("debug_info", null,
                        (config, value) -> config.showDebugInfo = value,
                        config -> config.showDebugInfo))
                .build();
    }

    private static OptionGroup explosives(BooleanSupplier enabled) {
        return OptionGroup.createBuilder()
                .setId(group("explosives"))
                .add(cycling("tnt", ExplosiveLightingMode.class, ExplosiveLightingMode.values(),
                        (config, value) -> config.tntLighting = value,
                        config -> config.tntLighting, enabled))
                .add(cycling("creeper", ExplosiveLightingMode.class, ExplosiveLightingMode.values(),
                        (config, value) -> config.creeperLighting = value,
                        config -> config.creeperLighting, enabled))
                .build();
    }

    private static OptionImpl<DynamicLightsConfig, DynamicLightsMode> mode() {
        return OptionImpl.createBuilder(DynamicLightsMode.class, STORAGE)
                .setId(option("mode", DynamicLightsMode.class))
                .setName(TextComponent.translatable(LANG + "mode.name"))
                .setTooltip(TextComponent.translatable(LANG + "mode.tooltip"))
                .setControl(opt -> new CyclingControl<>(opt, DynamicLightsMode.values(),
                        localizedNames(DynamicLightsMode.values())))
                .setBinding((config, value) -> config.mode = value, config -> config.mode)
                .setImpact(OptionImpact.MEDIUM)
                .build();
    }

    // ------------------------------------------------------------------------------------------
    // Per-type toggles
    // ------------------------------------------------------------------------------------------

    /**
     * One toggle per registered entity and block entity type, grouped by the mod that owns it.
     *
     * <p>Read straight from the registries — unlike the Extras particle toggles, no discovery is
     * needed, because 1.12.2 does register both of these by name. Wrapped in a guard all the same: a
     * mod with a malformed registry entry should cost its own group, not the whole tab.
     */
    private static void addTypeGroups(List<OptionGroup> groups, BooleanSupplier enabled) {
        try {
            addTypeGroups(groups, "entity", LightSourceSettings.listEntityTypes(), enabled,
                    (settings, id) -> !settings.isEntityTypeDisabled(id),
                    LightSourceSettings::setEntityTypeEnabled);
        } catch (Throwable t) {
            DynamicLights.LOGGER.warn("Could not build the per-entity light source toggles", t);
        }

        try {
            addTypeGroups(groups, "block_entity", LightSourceSettings.listBlockEntityTypes(), enabled,
                    (settings, id) -> !settings.isBlockEntityTypeDisabled(id),
                    LightSourceSettings::setBlockEntityTypeEnabled);
        } catch (Throwable t) {
            DynamicLights.LOGGER.warn("Could not build the per-block-entity light source toggles", t);
        }
    }

    private static void addTypeGroups(List<OptionGroup> groups, String kind, Map<String, String> types,
                                      BooleanSupplier enabled,
                                      java.util.function.BiPredicate<LightSourceSettings, String> getter,
                                      TypeSetter setter) {
        if (types.isEmpty()) {
            return;
        }

        LightSourceSettings settings = LightSourceSettings.getInstance();

        // Grouped by namespace so a pack with two hundred entity types does not produce one
        // two-hundred-row group. TreeMap keeps both the groups and the rows in a stable order.
        Map<String, Map<String, String>> byNamespace = new TreeMap<>();
        for (Map.Entry<String, String> type : types.entrySet()) {
            String id = type.getKey();
            int colon = id.indexOf(':');
            String namespace = colon > 0 ? id.substring(0, colon) : "unknown";

            byNamespace.computeIfAbsent(namespace, key -> new TreeMap<>()).put(id, type.getValue());
        }

        for (Map.Entry<String, Map<String, String>> namespaceEntry : byNamespace.entrySet()) {
            String namespace = namespaceEntry.getKey();

            OptionGroup.Builder builder = OptionGroup.createBuilder()
                    .setId(group(kind + "." + namespace));

            for (Map.Entry<String, String> type : namespaceEntry.getValue().entrySet()) {
                String id = type.getKey();

                builder.add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setId(option(kind + ".type." + id, boolean.class))
                        .setName(TextComponent.literal(type.getValue() + " (" + namespace + ")"))
                        .setTooltip(TextComponent.literal(id))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (config, value) -> setter.set(settings, id, value),
                                config -> getter.test(settings, id))
                        .setEnabledPredicate(enabled)
                        .build());
            }

            groups.add(builder.build());
        }
    }

    /** {@code LightSourceSettings::setEntityTypeEnabled} and friends, as a target type. */
    @FunctionalInterface
    private interface TypeSetter {
        void set(LightSourceSettings settings, String id, boolean enabled);
    }

    // ------------------------------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------------------------------

    private static OptionImpl<DynamicLightsConfig, Boolean> toggle(
            String key, BooleanSupplier enabled,
            BiConsumer<DynamicLightsConfig, Boolean> setter,
            Function<DynamicLightsConfig, Boolean> getter) {
        OptionImpl.Builder<DynamicLightsConfig, Boolean> builder =
                OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setId(option(key, boolean.class))
                        .setName(TextComponent.translatable(LANG + key + ".name"))
                        .setTooltip(TextComponent.translatable(LANG + key + ".tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding(setter, getter);

        if (enabled != null) {
            builder.setEnabledPredicate(enabled);
        }

        return builder.build();
    }

    private static <T extends Enum<T> & DynamicLightsConfig.Localized>
            OptionImpl<DynamicLightsConfig, T> cycling(
            String key, Class<T> type, T[] values,
            BiConsumer<DynamicLightsConfig, T> setter, Function<DynamicLightsConfig, T> getter,
            BooleanSupplier enabled) {
        OptionImpl.Builder<DynamicLightsConfig, T> builder = OptionImpl.createBuilder(type, STORAGE)
                .setId(option(key, type))
                .setName(TextComponent.translatable(LANG + key + ".name"))
                .setTooltip(TextComponent.translatable(LANG + key + ".tooltip"))
                .setControl(opt -> new CyclingControl<>(opt, values, localizedNames(values)))
                .setBinding(setter, getter);

        if (enabled != null) {
            builder.setEnabledPredicate(enabled);
        }

        return builder.build();
    }

    private static TextComponent[] localizedNames(DynamicLightsConfig.Localized[] values) {
        TextComponent[] names = new TextComponent[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = TextComponent.translatable(values[i].translationKey());
        }
        return names;
    }

    private static OptionIdentifier<Void> group(String path) {
        return OptionIdentifier.create(MOD_ID, "dynamiclights/" + path);
    }

    private static <T> OptionIdentifier<T> option(String path, Class<T> type) {
        return OptionIdentifier.create(MOD_ID, "dynamiclights/" + path, type);
    }
}
