package com.bdmajora.extras.gui;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import com.bdmajora.extras.client.AdaptiveSync;
import com.bdmajora.extras.client.particle.ParticleClassRegistry;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.control.ControlValueFormatter;
import com.bdmajora.impetus.api.options.control.CyclingControl;
import com.bdmajora.impetus.api.options.control.SliderControl;
import com.bdmajora.impetus.api.options.control.TickBoxControl;
import com.bdmajora.impetus.api.options.structure.OptionFlag;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.options.structure.OptionImpact;
import com.bdmajora.impetus.api.options.structure.OptionImpl;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.google.common.collect.ImmutableList;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The Extras page: everything Sodium Extra contributes, plus the finer OptiFine switches, as one tab
 * alongside General, Quality and Performance.
 *
 * <p>Upstream spreads this across five tabs (Animations, Particles, Details, Render, Extras). They
 * are groups here instead — Impetus' options screen already carries eight tabs, and the search bar
 * makes a long page navigable in a way a wide tab strip does not.
 *
 * <p>Sub-options are gated with {@code setEnabledPredicate} rather than hidden, so turning off a
 * master switch greys out what it governs instead of making controls appear and disappear as the
 * page is used.
 */
public final class ExtrasOptionPages {
    private static final String MOD_ID = "impetus";
    private static final String LANG = "impetus.options.extras.";

    private static final ExtrasOptionsStorage STORAGE = new ExtrasOptionsStorage();

    private ExtrasOptionPages() {
    }

    public static OptionPage extras() {
        List<OptionGroup> groups = new ArrayList<>();

        // Built here rather than inside particles() so the per-class toggles can gate on the same
        // instance: reading the saved config instead would leave them lit until Apply, while every
        // other sub-option on the page greys out the moment its master is unticked.
        OptionImpl<ExtrasConfig, Boolean> particlesMaster = toggle("particles.all",
                (config, value) -> config.particle.all = value,
                config -> config.particle.all,
                OptionImpact.HIGH, null, null);

        groups.add(animations());
        groups.add(particles(particlesMaster));
        addParticleClassGroups(groups, particlesMaster::getValue);
        groups.add(details());
        groups.add(fog());
        groups.add(cloudsAndWeather());
        groups.add(entityRendering());
        groups.add(overlay());
        groups.add(toasts());
        groups.add(qualityOfLife());

        return new OptionPage(
                OptionIdentifier.create(MOD_ID, "extras"),
                TextComponent.translatable("impetus.options.pages.extras"),
                ImmutableList.copyOf(groups));
    }

    // ------------------------------------------------------------------------------------------
    // Groups
    // ------------------------------------------------------------------------------------------

    private static OptionGroup animations() {
        OptionImpl<ExtrasConfig, Boolean> master = toggle("animations.all",
                (config, value) -> config.animation.all = value,
                config -> config.animation.all,
                OptionImpact.MEDIUM, OptionFlag.REQUIRES_ASSET_RELOAD, null);
        BooleanSupplier enabled = master::getValue;

        return OptionGroup.createBuilder()
                .setId(group("animations"))
                .add(master)
                .add(animationToggle("animations.water", enabled,
                        (config, value) -> config.animation.water = value, config -> config.animation.water))
                .add(animationToggle("animations.lava", enabled,
                        (config, value) -> config.animation.lava = value, config -> config.animation.lava))
                .add(animationToggle("animations.fire", enabled,
                        (config, value) -> config.animation.fire = value, config -> config.animation.fire))
                .add(animationToggle("animations.portal", enabled,
                        (config, value) -> config.animation.portal = value, config -> config.animation.portal))
                .add(animationToggle("animations.redstone", enabled,
                        (config, value) -> config.animation.redstone = value, config -> config.animation.redstone))
                .add(animationToggle("animations.explosion", enabled,
                        (config, value) -> config.animation.explosion = value, config -> config.animation.explosion))
                .add(animationToggle("animations.flame", enabled,
                        (config, value) -> config.animation.flame = value, config -> config.animation.flame))
                .add(animationToggle("animations.smoke", enabled,
                        (config, value) -> config.animation.smoke = value, config -> config.animation.smoke))
                .add(animationToggle("animations.sculk_sensor", enabled,
                        (config, value) -> config.animation.sculkSensor = value, config -> config.animation.sculkSensor))
                .add(animationToggle("animations.block", enabled,
                        (config, value) -> config.animation.blockAnimations = value,
                        config -> config.animation.blockAnimations))
                .build();
    }

    private static OptionGroup particles(OptionImpl<ExtrasConfig, Boolean> master) {
        BooleanSupplier enabled = master::getValue;

        return OptionGroup.createBuilder()
                .setId(group("particles"))
                .add(master)
                .add(toggle("particles.rain_splash", (config, value) -> config.particle.rainSplash = value,
                        config -> config.particle.rainSplash, null, null, enabled))
                .add(toggle("particles.block_break", (config, value) -> config.particle.blockBreak = value,
                        config -> config.particle.blockBreak, null, null, enabled))
                .add(toggle("particles.block_breaking", (config, value) -> config.particle.blockBreaking = value,
                        config -> config.particle.blockBreaking, null, null, enabled))
                .add(toggle("particles.void", (config, value) -> config.particle.voidParticles = value,
                        config -> config.particle.voidParticles, null, null, enabled))
                .add(toggle("particles.water", (config, value) -> config.particle.waterParticles = value,
                        config -> config.particle.waterParticles, null, null, enabled))
                .add(toggle("particles.portal", (config, value) -> config.particle.portalParticles = value,
                        config -> config.particle.portalParticles, null, null, enabled))
                .add(toggle("particles.potion", (config, value) -> config.particle.potionParticles = value,
                        config -> config.particle.potionParticles, null, null, enabled))
                .add(toggle("particles.dripping", (config, value) -> config.particle.drippingWaterLava = value,
                        config -> config.particle.drippingWaterLava, null, null, enabled))
                .add(toggle("particles.firework", (config, value) -> config.particle.fireworkParticles = value,
                        config -> config.particle.fireworkParticles, null, null, enabled))
                .build();
    }

    private static OptionGroup details() {
        OptionImpl<ExtrasConfig, Boolean> stars = toggle("details.stars",
                (config, value) -> config.detail.stars = value,
                config -> config.detail.stars,
                null, OptionFlag.REQUIRES_RENDERER_RELOAD, null);
        BooleanSupplier starsOn = stars::getValue;

        return OptionGroup.createBuilder()
                .setId(group("details"))
                .add(toggle("details.sky", (config, value) -> config.detail.sky = value,
                        config -> config.detail.sky, null, OptionFlag.REQUIRES_RENDERER_RELOAD, null))
                .add(stars)
                .add(slider("details.total_stars",
                        ExtrasConfig.DetailSettings.STARS_MIN, ExtrasConfig.DetailSettings.STARS_MAX, 500,
                        ControlValueFormatter.number(),
                        (config, value) -> config.detail.totalStars = value,
                        config -> config.detail.totalStars,
                        OptionImpact.MEDIUM, OptionFlag.REQUIRES_RENDERER_RELOAD, starsOn))
                .add(toggle("details.sun", (config, value) -> config.detail.sun = value,
                        config -> config.detail.sun, null, null, null))
                .add(toggle("details.moon", (config, value) -> config.detail.moon = value,
                        config -> config.detail.moon, null, null, null))
                .add(toggle("details.rain_snow", (config, value) -> config.detail.rainSnow = value,
                        config -> config.detail.rainSnow, OptionImpact.LOW, null, null))
                .add(toggle("details.biome_colors", (config, value) -> config.detail.biomeColors = value,
                        config -> config.detail.biomeColors, OptionImpact.MEDIUM,
                        OptionFlag.REQUIRES_RENDERER_RELOAD, null))
                .add(toggle("details.sky_colors", (config, value) -> config.detail.skyColors = value,
                        config -> config.detail.skyColors, null, null, null))
                .add(toggle("details.swamp_colors", (config, value) -> config.detail.swampColors = value,
                        config -> config.detail.swampColors, null, OptionFlag.REQUIRES_RENDERER_RELOAD, null))
                .add(toggle("details.void_fog", (config, value) -> config.detail.voidFog = value,
                        config -> config.detail.voidFog, null, null, null))
                .add(toggle("details.capes", (config, value) -> config.detail.showCapes = value,
                        config -> config.detail.showCapes, null, null, null))
                .add(toggle("details.held_item_tooltips", (config, value) -> config.detail.heldItemTooltips = value,
                        config -> config.detail.heldItemTooltips, null, null, null))
                .build();
    }

    private static OptionGroup fog() {
        OptionImpl<ExtrasConfig, Boolean> master = toggle("render.fog",
                (config, value) -> config.render.fog = value,
                config -> config.render.fog, OptionImpact.LOW, null, null);
        BooleanSupplier enabled = master::getValue;

        return OptionGroup.createBuilder()
                .setId(group("fog"))
                .add(master)
                .add(slider("render.fog_start", 0, 200, 10, ControlValueFormatter.percentage(),
                        (config, value) -> config.render.fogStart = value,
                        config -> config.render.fogStart, null, null, enabled))
                .add(slider("render.fog_distance", 0, 32, 1,
                        ControlValueFormatter.quantityOrDisabled("chunks", "Default"),
                        (config, value) -> config.render.fogDistance = value,
                        config -> config.render.fogDistance, OptionImpact.MEDIUM, null, enabled))
                .add(cycling("render.fog_shape", ExtrasConfig.FogShape.class, ExtrasConfig.FogShape.values(),
                        (config, value) -> config.render.fogShape = value,
                        config -> config.render.fogShape, enabled))
                .build();
    }

    private static OptionGroup cloudsAndWeather() {
        return OptionGroup.createBuilder()
                .setId(group("clouds"))
                .add(slider("render.cloud_scale",
                        ExtrasConfig.RenderSettings.CLOUD_SCALE_MIN,
                        ExtrasConfig.RenderSettings.CLOUD_SCALE_MAX, 1,
                        value -> TextComponent.literal(String.format(Locale.ROOT, "%.2fx",
                                (float) value / ExtrasConfig.RenderSettings.CLOUD_SCALE_VANILLA)),
                        (config, value) -> config.render.cloudScale = value,
                        config -> config.render.cloudScale, null, null, null))
                .add(cycling("render.cloud_translucency", ExtrasConfig.CloudTranslucency.class,
                        ExtrasConfig.CloudTranslucency.values(),
                        (config, value) -> config.render.cloudTranslucency = value,
                        config -> config.render.cloudTranslucency, null))
                .add(cycling("time", ExtrasConfig.TimeOverride.class, ExtrasConfig.TimeOverride.values(),
                        (config, value) -> config.extra.timeOverride = value,
                        config -> config.extra.timeOverride, null))
                .add(cycling("weather", ExtrasConfig.WeatherOverride.class, ExtrasConfig.WeatherOverride.values(),
                        (config, value) -> config.extra.weatherOverride = value,
                        config -> config.extra.weatherOverride, null))
                .build();
    }

    private static OptionGroup entityRendering() {
        OptionImpl<ExtrasConfig, Boolean> itemFrames = toggle("render.item_frames",
                (config, value) -> config.render.itemFrames = value,
                config -> config.render.itemFrames, OptionImpact.MEDIUM, null, null);
        BooleanSupplier itemFramesOn = itemFrames::getValue;

        OptionImpl<ExtrasConfig, Boolean> beacons = toggle("render.beacons",
                (config, value) -> config.render.beacons = value,
                config -> config.render.beacons, OptionImpact.LOW, null, null);
        BooleanSupplier beaconsOn = beacons::getValue;

        return OptionGroup.createBuilder()
                .setId(group("entities"))
                .add(itemFrames)
                .add(slider("render.item_frame_lod", 0, 256, 8,
                        ControlValueFormatter.quantityOrDisabled("blocks", "Off"),
                        (config, value) -> config.render.itemFrameLodDistance = value,
                        config -> config.render.itemFrameLodDistance, OptionImpact.LOW, null, itemFramesOn))
                .add(toggle("render.item_frame_name_tag", (config, value) -> config.render.itemFrameNameTag = value,
                        config -> config.render.itemFrameNameTag, null, null, itemFramesOn))
                .add(toggle("render.armor_stands", (config, value) -> config.render.armorStands = value,
                        config -> config.render.armorStands, OptionImpact.LOW, null, null))
                .add(toggle("render.paintings", (config, value) -> config.render.paintings = value,
                        config -> config.render.paintings, null, null, null))
                .add(toggle("render.player_name_tag", (config, value) -> config.render.playerNameTag = value,
                        config -> config.render.playerNameTag, null, null, null))
                .add(toggle("render.dropped_items", (config, value) -> config.render.droppedItemsFancy = value,
                        config -> config.render.droppedItemsFancy, OptionImpact.MEDIUM, null, null))
                .add(beacons)
                .add(toggle("render.limit_beacon_beam", (config, value) -> config.render.limitBeaconBeamHeight = value,
                        config -> config.render.limitBeaconBeamHeight, null, null, beaconsOn))
                .add(toggle("render.enchanting_books", (config, value) -> config.render.enchantingTableBooks = value,
                        config -> config.render.enchantingTableBooks, null, null, null))
                .add(toggle("render.pistons", (config, value) -> config.render.pistons = value,
                        config -> config.render.pistons, OptionImpact.LOW, null, null))
                .build();
    }

    private static OptionGroup overlay() {
        OptionImpl<ExtrasConfig, Boolean> showFps = toggle("overlay.fps",
                (config, value) -> config.extra.showFps = value,
                config -> config.extra.showFps, null, null, null);
        BooleanSupplier fpsOn = showFps::getValue;

        OptionImpl<ExtrasConfig, Boolean> showCoords = toggle("overlay.coords",
                (config, value) -> config.extra.showCoords = value,
                config -> config.extra.showCoords, null, null, null);
        BooleanSupplier coordsOn = showCoords::getValue;

        return OptionGroup.createBuilder()
                .setId(group("overlay"))
                .add(showFps)
                .add(toggle("overlay.fps_extended", (config, value) -> config.extra.showFpsExtended = value,
                        config -> config.extra.showFpsExtended, null, null, fpsOn))
                .add(showCoords)
                .add(toggle("overlay.ignore_reduced_debug_info",
                        (config, value) -> config.extra.ignoreReducedDebugInfo = value,
                        config -> config.extra.ignoreReducedDebugInfo, null, null, coordsOn))
                .add(cycling("overlay.corner", ExtrasConfig.OverlayCorner.class,
                        ExtrasConfig.OverlayCorner.values(),
                        (config, value) -> config.extra.overlayCorner = value,
                        config -> config.extra.overlayCorner, null))
                .add(cycling("overlay.text_contrast", ExtrasConfig.TextContrast.class,
                        ExtrasConfig.TextContrast.values(),
                        (config, value) -> config.extra.textContrast = value,
                        config -> config.extra.textContrast, null))
                .build();
    }

    private static OptionGroup toasts() {
        OptionImpl<ExtrasConfig, Boolean> master = toggle("toasts.all",
                (config, value) -> config.extra.toasts = value,
                config -> config.extra.toasts, null, null, null);
        BooleanSupplier enabled = master::getValue;

        return OptionGroup.createBuilder()
                .setId(group("toasts"))
                .add(master)
                .add(toggle("toasts.advancement", (config, value) -> config.extra.toastAdvancement = value,
                        config -> config.extra.toastAdvancement, null, null, enabled))
                .add(toggle("toasts.recipe", (config, value) -> config.extra.toastRecipe = value,
                        config -> config.extra.toastRecipe, null, null, enabled))
                .add(toggle("toasts.tutorial", (config, value) -> config.extra.toastTutorial = value,
                        config -> config.extra.toastTutorial, null, null, enabled))
                .add(toggle("toasts.system", (config, value) -> config.extra.toastSystem = value,
                        config -> config.extra.toastSystem, null, null, enabled))
                .build();
    }

    private static OptionGroup qualityOfLife() {
        OptionImpl<ExtrasConfig, Boolean> steadyHud = toggle("steady_debug_hud",
                (config, value) -> config.extra.steadyDebugHud = value,
                config -> config.extra.steadyDebugHud, OptionImpact.LOW, null, null);
        BooleanSupplier steadyHudOn = steadyHud::getValue;

        OptionImpl<ExtrasConfig, Boolean> panini = toggle("panini",
                (config, value) -> config.extra.paniniProjection = value,
                config -> config.extra.paniniProjection, OptionImpact.MEDIUM, null, null);
        BooleanSupplier paniniOn = panini::getValue;

        OptionGroup.Builder builder = OptionGroup.createBuilder()
                .setId(group("misc"))
                .add(verticalSync())
                .add(steadyHud)
                .add(slider("steady_debug_hud_refresh",
                        ExtrasConfig.ExtraSettings.STEADY_HUD_REFRESH_MIN,
                        ExtrasConfig.ExtraSettings.STEADY_HUD_REFRESH_MAX, 1,
                        value -> TextComponent.literal(value + (value == 1 ? " tick" : " ticks")),
                        (config, value) -> config.extra.steadyDebugHudRefreshInterval = value,
                        config -> config.extra.steadyDebugHudRefreshInterval, null, null, steadyHudOn))
                .add(panini)
                .add(slider("panini_strength", 0, 100, 5, ControlValueFormatter.percentage(),
                        (config, value) -> config.extra.paniniProjectionStrength = value,
                        config -> config.extra.paniniProjectionStrength, null, null, paniniOn))
                .add(advancedItemTooltips())
                .add(toggle("mod_name_tooltip", (config, value) -> config.extra.modNameTooltip = value,
                        config -> config.extra.modNameTooltip, null, null, null))
                .add(toggle("light_updates", (config, value) -> config.render.lightUpdates = value,
                        config -> config.render.lightUpdates, OptionImpact.HIGH, null, null))
                .add(toggle("prevent_shaders", (config, value) -> config.render.preventShaders = value,
                        config -> config.render.preventShaders, null, null, null))
                .add(toggle("profile_entity_rendering",
                        (config, value) -> config.render.profileEntityRendering = value,
                        config -> config.render.profileEntityRendering, null, null, null))
                .add(slider("autosave_interval",
                        ExtrasConfig.ExtraSettings.AUTOSAVE_MIN_TICKS,
                        ExtrasConfig.ExtraSettings.AUTOSAVE_MAX_TICKS, 300,
                        value -> value == 0
                                ? TextComponent.translatable("options.off")
                                : TextComponent.literal(value / 20 + "s"),
                        (config, value) -> config.extra.autosaveInterval = value,
                        config -> config.extra.autosaveInterval, null, null, null));

        return builder.build();
    }

    // ------------------------------------------------------------------------------------------
    // Options that do not bind to the Extras config
    // ------------------------------------------------------------------------------------------

    /**
     * Vertical sync, folding adaptive sync in with the vanilla on/off pair.
     *
     * <p>The available values are resolved once when the page is built rather than being a fixed
     * list: offering ADAPTIVE where the driver has no tear control would give the user a setting
     * that silently does nothing.
     */
    private static OptionImpl<ExtrasConfig, ExtrasConfig.VerticalSync> verticalSync() {
        ExtrasConfig.VerticalSync[] available = AdaptiveSync.isSupported()
                ? ExtrasConfig.VerticalSync.values()
                : new ExtrasConfig.VerticalSync[] {
                        ExtrasConfig.VerticalSync.OFF, ExtrasConfig.VerticalSync.ON };

        return OptionImpl.createBuilder(ExtrasConfig.VerticalSync.class, STORAGE)
                .setId(option("vertical_sync", ExtrasConfig.VerticalSync.class))
                .setName(TextComponent.translatable(LANG + "vertical_sync.name"))
                .setTooltip(TextComponent.translatable(LANG + "vertical_sync.tooltip"))
                .setControl(opt -> new CyclingControl<>(opt, available, localizedNames(available)))
                .setBinding((config, value) -> AdaptiveSync.apply(value), config -> AdaptiveSync.current())
                .setImpact(OptionImpact.VARIES)
                .build();
    }

    /**
     * Vanilla's advanced tooltips, which otherwise have no home but the F3+H chord.
     *
     * <p>Bound straight to the vanilla setting rather than mirrored into the Extras config, so
     * pressing F3+H and using this control cannot disagree.
     */
    private static OptionImpl<ExtrasConfig, Boolean> advancedItemTooltips() {
        return OptionImpl.createBuilder(boolean.class, STORAGE)
                .setId(option("advanced_item_tooltips", boolean.class))
                .setName(TextComponent.translatable(LANG + "advanced_item_tooltips.name"))
                .setTooltip(TextComponent.translatable(LANG + "advanced_item_tooltips.tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding(
                        (config, value) -> {
                            Minecraft.getMinecraft().gameSettings.advancedItemTooltips = value;
                            Minecraft.getMinecraft().gameSettings.saveOptions();
                        },
                        config -> Minecraft.getMinecraft().gameSettings.advancedItemTooltips)
                .build();
    }

    // ------------------------------------------------------------------------------------------
    // Per-class particle toggles
    // ------------------------------------------------------------------------------------------

    /**
     * One toggle per discovered particle class, grouped by owning mod.
     *
     * <p>Everything here is discovered at runtime — see {@link ParticleClassRegistry} for why 1.12.2
     * leaves no other option — so a discovery failure must not be able to take the rest of the page
     * with it. Hence the guard: a broken particle scan costs its own group, not the whole tab.
     */
    private static void addParticleClassGroups(List<OptionGroup> groups, BooleanSupplier particlesOn) {
        try {
            ParticleClassRegistry registry = ParticleClassRegistry.getInstance();
            registry.scanFactories(Minecraft.getMinecraft().effectRenderer);

            Map<String, String> discovered = registry.getDiscoveredClasses();
            if (discovered.isEmpty()) {
                return;
            }

            Map<String, List<Map.Entry<String, String>>> byMod = new TreeMap<>();
            for (Map.Entry<String, String> entry : discovered.entrySet()) {
                String modId = registry.getModId(entry.getKey());
                byMod.computeIfAbsent(modId == null ? "unknown" : modId, key -> new ArrayList<>()).add(entry);
            }

            for (Map.Entry<String, List<Map.Entry<String, String>>> modEntry : byMod.entrySet()) {
                String modId = modEntry.getKey();
                List<Map.Entry<String, String>> classes = modEntry.getValue();
                classes.sort(Comparator.comparing(Map.Entry::getValue));

                OptionGroup.Builder builder = OptionGroup.createBuilder()
                        .setId(group("particles." + modId));

                for (Map.Entry<String, String> classEntry : classes) {
                    String fullName = classEntry.getKey();
                    String simpleName = classEntry.getValue();

                    builder.add(OptionImpl.createBuilder(boolean.class, STORAGE)
                            .setId(option("particles.class." + fullName, boolean.class))
                            .setName(TextComponent.literal(simpleName + " (" + modId + ")"))
                            .setTooltip(TextComponent.literal(fullName))
                            .setControl(TickBoxControl::new)
                            .setBinding(
                                    (config, value) -> registry.setClassEnabled(fullName, value),
                                    config -> !registry.isClassDisabled(fullName))
                            .setEnabledPredicate(particlesOn)
                            .build());
                }

                groups.add(builder.build());
            }
        } catch (Throwable t) {
            Extras.LOGGER.warn("Could not build the per-class particle toggles", t);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------------------------------

    /** A texture-animation sub-switch: always asset-reloading, always gated on the master. */
    private static OptionImpl<ExtrasConfig, Boolean> animationToggle(
            String key, BooleanSupplier enabled,
            BiConsumer<ExtrasConfig, Boolean> setter, Function<ExtrasConfig, Boolean> getter) {
        return toggle(key, setter, getter, null, OptionFlag.REQUIRES_ASSET_RELOAD, enabled);
    }

    private static OptionImpl<ExtrasConfig, Boolean> toggle(
            String key,
            BiConsumer<ExtrasConfig, Boolean> setter, Function<ExtrasConfig, Boolean> getter,
            OptionImpact impact, OptionFlag flag, BooleanSupplier enabled) {
        OptionImpl.Builder<ExtrasConfig, Boolean> builder = OptionImpl.createBuilder(boolean.class, STORAGE)
                .setId(option(key, boolean.class))
                .setName(TextComponent.translatable(LANG + key + ".name"))
                .setTooltip(TextComponent.translatable(LANG + key + ".tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding(setter, getter);

        if (impact != null) {
            builder.setImpact(impact);
        }
        if (flag != null) {
            builder.setFlags(flag);
        }
        if (enabled != null) {
            builder.setEnabledPredicate(enabled);
        }

        return builder.build();
    }

    private static OptionImpl<ExtrasConfig, Integer> slider(
            String key, int min, int max, int step, ControlValueFormatter formatter,
            BiConsumer<ExtrasConfig, Integer> setter, Function<ExtrasConfig, Integer> getter,
            OptionImpact impact, OptionFlag flag, BooleanSupplier enabled) {
        OptionImpl.Builder<ExtrasConfig, Integer> builder = OptionImpl.createBuilder(int.class, STORAGE)
                .setId(option(key, int.class))
                .setName(TextComponent.translatable(LANG + key + ".name"))
                .setTooltip(TextComponent.translatable(LANG + key + ".tooltip"))
                .setControl(opt -> new SliderControl(opt, min, max, step, formatter))
                .setBinding(setter, getter);

        if (impact != null) {
            builder.setImpact(impact);
        }
        if (flag != null) {
            builder.setFlags(flag);
        }
        if (enabled != null) {
            builder.setEnabledPredicate(enabled);
        }

        return builder.build();
    }

    private static <T extends Enum<T> & ExtrasConfig.Localized> OptionImpl<ExtrasConfig, T> cycling(
            String key, Class<T> type, T[] values,
            BiConsumer<ExtrasConfig, T> setter, Function<ExtrasConfig, T> getter,
            BooleanSupplier enabled) {
        OptionImpl.Builder<ExtrasConfig, T> builder = OptionImpl.createBuilder(type, STORAGE)
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

    private static TextComponent[] localizedNames(ExtrasConfig.Localized[] values) {
        TextComponent[] names = new TextComponent[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = TextComponent.translatable(values[i].translationKey());
        }
        return names;
    }

    private static OptionIdentifier<Void> group(String path) {
        return OptionIdentifier.create(MOD_ID, "extras/" + path);
    }

    private static <T> OptionIdentifier<T> option(String path, Class<T> type) {
        return OptionIdentifier.create(MOD_ID, "extras/" + path, type);
    }
}
