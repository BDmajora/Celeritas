package com.bdmajora.impetus.impl.gui;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.control.ControlValueFormatter;
import com.bdmajora.impetus.api.options.control.CyclingControl;
import com.bdmajora.impetus.api.options.control.ReadOnlyStringControl;
import com.bdmajora.impetus.api.options.control.SliderControl;
import com.bdmajora.impetus.api.options.control.TickBoxControl;
import com.bdmajora.impetus.engine.impl.common.util.NativeBuffer;
import com.bdmajora.impetus.engine.impl.gui.ImpetusGameOptions;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.render.chunk.region.RenderRegionManager;
import org.lwjgl.opengl.Display;
import com.bdmajora.impetus.ImpetusVintage;
import com.bdmajora.impetus.api.options.structure.OptionFlag;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.options.structure.OptionImpact;
import com.bdmajora.impetus.api.options.structure.OptionImpl;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.api.options.structure.OptionStorage;
import com.bdmajora.impetus.api.options.structure.StandardOptions;
import com.bdmajora.impetus.impl.compat.modernui.MuiGuiScaleHook;
import com.bdmajora.impetus.impl.render.terrain.compile.task.ChunkBuilderMeshingTask;

import java.util.ArrayList;
import java.util.List;

public class ImpetusGameOptionPages {
    private static final ImpetusGameOptions sodiumOpts = ImpetusVintage.options();
    private static final MinecraftOptionsStorage vanillaOpts = new MinecraftOptionsStorage();
    private static final MenuState menuState = new MenuState();
    private static final OptionStorage<MenuState> menuOpts = () -> menuState;

    private static int computeMaxRangeForRenderDistance(@SuppressWarnings("SameParameterValue") int injectedRenderDistance) {
        return injectedRenderDistance;
    }

    private static void applyLeavesQuality(boolean seeThrough) {
        Blocks.LEAVES.setGraphicsLevel(seeThrough);
        Blocks.LEAVES2.setGraphicsLevel(seeThrough);
    }

    private static OptionImpl<MenuState, String> readOnlyOption(OptionIdentifier<Void> id, TextComponent name,
                                                                TextComponent tooltip, String value) {
        return readOnlyOption(id, name, tooltip, value, false);
    }

    private static OptionImpl<MenuState, String> displayOnlyOption(OptionIdentifier<Void> id, TextComponent name,
                                                                   TextComponent tooltip, String value) {
        return readOnlyOption(id, name, tooltip, value, true);
    }

    private static OptionImpl<MenuState, String> readOnlyOption(OptionIdentifier<Void> id, TextComponent name,
                                                                TextComponent tooltip, String value, boolean enabled) {
        return OptionImpl.createBuilder(String.class, menuOpts)
                .setId(id.cast())
                .setName(name)
                .setTooltip(tooltip)
                .setControl(ReadOnlyStringControl::new)
                .setBinding((state, nextValue) -> { }, state -> value)
                .setEnabled(enabled)
                .build();
    }

    public static OptionPage general() {
        List<OptionGroup> groups = new ArrayList<>();
        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.RENDERING)
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.RENDER_DISTANCE.cast())
                        .setName(TextComponent.literal(I18n.format("options.renderDistance")))
                        .setTooltip(TextComponent.translatable("impetus.options.view_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 2, computeMaxRangeForRenderDistance(32), 1, ControlValueFormatter.translateVariable("options.chunks")))
                        .setBinding((options, value) -> options.renderDistanceChunks = value, options -> options.renderDistanceChunks)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(String.class, vanillaOpts)
                        .setId(StandardOptions.Option.SIMULATION_DISTANCE.cast())
                        .setName(TextComponent.translatable("impetus.options.simulation_distance.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.simulation_distance.disabled_tooltip"))
                        .setControl(ReadOnlyStringControl::new)
                        .setBinding((opts, value) -> { }, opts -> I18n.format("options.chunks", opts.renderDistanceChunks))
                        .setEnabled(false)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.BRIGHTNESS.cast())
                        .setName(TextComponent.translatable("options.gamma"))
                        .setTooltip(TextComponent.translatable("impetus.options.brightness.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 100, 1, ControlValueFormatter.brightness()))
                        .setBinding((opts, value) -> opts.gammaSetting = (float) (value * 0.01D), (opts) -> (int) (opts.gammaSetting / 0.01D))
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.WINDOW)
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.GUI_SCALE.cast())
                        .setName(TextComponent.translatable("options.guiScale"))
                        .setTooltip(TextComponent.translatable("impetus.options.gui_scale.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, MuiGuiScaleHook.getMaxGuiScale(), 1, ControlValueFormatter.guiScale()))
                        .setBinding((opts, value) -> {
                            opts.guiScale = value;

                            Minecraft mc = Minecraft.getMinecraft();
                            mc.resize(mc.displayWidth, mc.displayHeight);
                        }, opts -> opts.guiScale)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setId(StandardOptions.Option.FULLSCREEN.cast())
                        .setName(TextComponent.translatable("options.fullscreen"))
                        .setTooltip(TextComponent.translatable("impetus.options.fullscreen.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.fullScreen = value;

                            Minecraft client = Minecraft.getMinecraft();

                            if (client.isFullScreen() != opts.fullScreen) {
                                client.toggleFullscreen();

                                // The client might not be able to enter full-screen mode
                                opts.fullScreen = client.isFullScreen();
                            }
                        }, (opts) -> opts.fullScreen)
                        .build())
                .add(readOnlyOption(
                        StandardOptions.Option.FULLSCREEN_RESOLUTION,
                        TextComponent.translatable("impetus.options.fullscreen.resolution.name"),
                        TextComponent.translatable("impetus.options.fullscreen.resolution.disabled_tooltip"),
                        I18n.format("impetus.options.fullscreen.resolution.value")))
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setId(StandardOptions.Option.VSYNC.cast())
                        .setName(TextComponent.translatable("options.vsync"))
                        .setTooltip(TextComponent.translatable("impetus.options.v_sync.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.enableVsync = value;
                            Display.setVSyncEnabled(opts.enableVsync);
                        }, opts -> opts.enableVsync)
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.MAX_FRAMERATE.cast())
                        .setName(TextComponent.translatable("options.framerateLimit"))
                        .setTooltip(TextComponent.translatable("impetus.options.fps_limit.tooltip"))
                        .setControl(option -> new SliderControl(option, 10, 260, 10, ControlValueFormatter.fpsLimit()))
                        .setBinding((opts, value) -> opts.limitFramerate = value, opts -> opts.limitFramerate)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.INDICATORS)
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setId(StandardOptions.Option.VIEW_BOBBING.cast())
                        .setName(TextComponent.translatable("options.viewBobbing"))
                        .setTooltip(TextComponent.translatable("impetus.options.view_bobbing.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.viewBobbing = value, opts -> opts.viewBobbing)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.ATTACK_INDICATOR.cast())
                        .setName(TextComponent.translatable("options.attackIndicator"))
                        .setTooltip(TextComponent.translatable("impetus.options.attack_indicator.tooltip"))
                        .setControl(opts -> new CyclingControl<>(opts, new Integer[] { 0, 1, 2 }, new TextComponent[] {
                                TextComponent.translatable("options.off"),
                                TextComponent.translatable("options.attack.crosshair"),
                                TextComponent.translatable("options.attack.hotbar") }))
                        .setBinding((opts, value) -> opts.attackIndicator = value, (opts) -> opts.attackIndicator)
                        .build())
                .add(readOnlyOption(
                        StandardOptions.Option.AUTOSAVE_INDICATOR,
                        TextComponent.translatable("impetus.options.autosave_indicator.name"),
                        TextComponent.translatable("impetus.options.autosave_indicator.disabled_tooltip"),
                        I18n.format("impetus.options.unavailable")))
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.GRAPHICS)
                .add(displayOnlyOption(
                        StandardOptions.Option.GRAPHICS_API,
                        TextComponent.translatable("impetus.options.graphics_api.name"),
                        TextComponent.translatable("impetus.options.graphics_api.tooltip"),
                        "OpenGL"))
                .build());

        return new OptionPage(StandardOptions.Pages.GENERAL, TextComponent.translatable("stat.generalButton"), ImmutableList.copyOf(groups));
    }

    public static OptionPage quality() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.GRAPHICS)
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setId(StandardOptions.Option.GRAPHICS_MODE.cast())
                        .setName(TextComponent.translatable("options.graphics"))
                        .setTooltip(TextComponent.translatable("impetus.options.graphics_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, new Boolean[] { false, true }, new TextComponent[] {
                                TextComponent.translatable("options.graphics.fast"),
                                TextComponent.translatable("options.graphics.fancy") }))
                        .setBinding((opts, value) -> opts.fancyGraphics = value, opts -> opts.fancyGraphics)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.DETAILS)
                .add(readOnlyOption(
                        StandardOptions.Option.IMPROVED_TRANSPARENCY,
                        TextComponent.translatable("impetus.options.improved_transparency.name"),
                        TextComponent.translatable("impetus.options.improved_transparency.tooltip"),
                        I18n.format("impetus.options.unavailable")))
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.CLOUDS.cast())
                        .setName(TextComponent.translatable("options.renderClouds"))
                        .setTooltip(TextComponent.translatable("impetus.options.clouds_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, new Integer[] { 0, 1, 2}, new TextComponent[] {
                                TextComponent.translatable("options.off"),
                                TextComponent.translatable("options.clouds.fast"),
                                TextComponent.translatable("options.clouds.fancy") }))
                        .setBinding((opts, value) -> {
                            opts.clouds = value;
                        }, opts -> {
                            return opts.clouds;
                        })
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.CLOUD_HEIGHT.cast())
                        .setName(TextComponent.translatable("impetus.options.cloud_height.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.cloud_height.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 256, 1, ControlValueFormatter.translateVariable("impetus.options.cloud_height.value")))
                        .setBinding((opts, value) -> opts.quality.cloudHeight = value, opts -> opts.quality.cloudHeight)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.CLOUD_DISTANCE.cast())
                        .setName(TextComponent.translatable("impetus.options.cloud_distance.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.cloud_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 8, 128, 8, ControlValueFormatter.translateVariable("impetus.options.cloud_distance.value")))
                        .setBinding((opts, value) -> opts.quality.cloudDistance = value, opts -> opts.quality.cloudDistance)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(ImpetusGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setId(StandardOptions.Option.WEATHER.cast())
                        .setName(TextComponent.translatable("soundCategory.weather"))
                        .setTooltip(TextComponent.translatable("impetus.options.weather_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, ImpetusGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.weatherQuality = value, opts -> opts.quality.weatherQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.WEATHER_EFFECT_RADIUS.cast())
                        .setName(TextComponent.translatable("impetus.options.weather_effect_radius.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.weather_effect_radius.tooltip"))
                        .setControl(option -> new SliderControl(option, 1, 32, 1, ControlValueFormatter.number()))
                        .setBinding((opts, value) -> opts.quality.weatherEffectRadius = value, opts -> opts.quality.weatherEffectRadius)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.SEE_THROUGH_LEAVES.cast())
                        .setName(TextComponent.translatable("impetus.options.see_through_leaves.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.see_through_leaves.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.quality.leavesQuality = value ? ImpetusGameOptions.GraphicsQuality.FANCY : ImpetusGameOptions.GraphicsQuality.FAST;
                            applyLeavesQuality(value);
                        },
                                opts -> opts.quality.leavesQuality.isFancy(Minecraft.getMinecraft().gameSettings.fancyGraphics))
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.PARTICLES.cast())
                        .setName(TextComponent.translatable("options.particles"))
                        .setTooltip(TextComponent.translatable("impetus.options.particle_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, new Integer[] { 0, 1, 2}, new TextComponent[] {
                                TextComponent.translatable("options.particles.all"),
                                TextComponent.translatable( "options.particles.decreased"),
                                TextComponent.translatable("options.particles.minimal") }))
                        .setBinding((opts, value) -> opts.particleSetting = value, (opts) -> opts.particleSetting)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.SMOOTH_LIGHT.cast())
                        .setName(TextComponent.translatable("options.ao"))
                        .setTooltip(TextComponent.translatable("impetus.options.smooth_lighting.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, new Integer[] { 0, 1, 2}, new TextComponent[] {
                                TextComponent.translatable("options.ao.off"),
                                TextComponent.translatable("options.ao.min"),
                                TextComponent.translatable("options.ao.max") }))
                        .setBinding((opts, value) -> opts.ambientOcclusion = value, opts -> opts.ambientOcclusion)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.BIOME_BLEND.cast())
                        .setName(TextComponent.translatable("impetus.options.biomeBlendRadius"))
                        .setTooltip(TextComponent.translatable("impetus.options.biome_blend.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 14, 1, ControlValueFormatter.biomeBlend()))
                        .setBinding((opts, value) -> opts.quality.legacyBiomeBlendRadius = value, opts -> opts.quality.legacyBiomeBlendRadius)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.ENTITY_DISTANCE.cast())
                        .setName(TextComponent.translatable("impetus.options.entity_distance.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.entity_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 50, 500, 25, ControlValueFormatter.percentage()))
                        .setBinding((opts, value) -> opts.quality.entityDistance = value, opts -> opts.quality.entityDistance)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setId(StandardOptions.Option.ENTITY_SHADOWS.cast())
                        .setName(TextComponent.translatable("options.entityShadows"))
                        .setTooltip(TextComponent.translatable("impetus.options.entity_shadows.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.entityShadows = value, opts -> opts.entityShadows)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.VIGNETTE.cast())
                        .setName(TextComponent.translatable("impetus.options.vignette.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.vignette.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.enableVignette = value, opts -> opts.quality.enableVignette)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.CHUNK_FADE_IN_DURATION.cast())
                        .setName(TextComponent.translatable("impetus.options.chunk_fade_in_duration.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.chunk_fade_in_duration.tooltip"))
                        .setControl(o -> new SliderControl(o, 0, 2000, 100, ControlValueFormatter.translateVariable("impetus.options.chunk_fade_in_duration.value")))
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.quality.chunkFadeInDuration = value, opts -> opts.quality.chunkFadeInDuration)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());


        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.MIPMAPS)
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.MIPMAP_LEVEL.cast())
                        .setName(TextComponent.translatable("options.mipmapLevels"))
                        .setTooltip(TextComponent.translatable("impetus.options.mipmap_levels.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 4, 1, ControlValueFormatter.multiplier()))
                        .setBinding((opts, value) -> opts.mipmapLevels = value, opts -> opts.mipmapLevels)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                        .build())
                .add(displayOnlyOption(
                        StandardOptions.Option.TEXTURE_FILTERING,
                        TextComponent.translatable("impetus.options.texture_filtering.name"),
                        TextComponent.translatable("impetus.options.texture_filtering.tooltip"),
                        I18n.format("impetus.options.texture_filtering.value")))
                .add(readOnlyOption(
                        StandardOptions.Option.ANISOTROPIC_FILTERING,
                        TextComponent.translatable("impetus.options.anisotropic_filtering.name"),
                        TextComponent.translatable("impetus.options.anisotropic_filtering.tooltip"),
                        I18n.format("options.off")))
                .add(displayOnlyOption(
                        StandardOptions.Option.TEXEL_INTERPOLATION,
                        TextComponent.translatable("impetus.options.texel_interpolation.name"),
                        TextComponent.translatable("impetus.options.texel_interpolation.tooltip"),
                        I18n.format("impetus.options.texel_interpolation.value")))
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.FLUIDS)
                .add(displayOnlyOption(
                        StandardOptions.Option.FLUID_CULLING,
                        TextComponent.translatable("impetus.options.fluid_culling.name"),
                        TextComponent.translatable("impetus.options.fluid_culling.tooltip"),
                        I18n.format("impetus.options.fluid_culling.value")))
                .add(displayOnlyOption(
                        StandardOptions.Option.FLUID_SHAPING,
                        TextComponent.translatable("impetus.options.fluid_shaping.name"),
                        TextComponent.translatable("impetus.options.fluid_shaping.tooltip"),
                        I18n.format("impetus.options.fluid_shaping.value")))
                .add(displayOnlyOption(
                        StandardOptions.Option.ENTITY_SORTING,
                        TextComponent.translatable("impetus.options.entity_sorting.name"),
                        TextComponent.translatable("impetus.options.entity_sorting.tooltip"),
                        I18n.format("impetus.options.entity_sorting.value")))
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.SORTING)
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.FAST_BLOCK_RENDERER.cast())
                        .setName(TextComponent.translatable("impetus.options.fast_block_renderer.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.fast_block_renderer.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> ChunkBuilderMeshingTask.USE_NEW_BLOCK_RENDERER = value, opts -> ChunkBuilderMeshingTask.USE_NEW_BLOCK_RENDERER)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        return new OptionPage(StandardOptions.Pages.QUALITY, TextComponent.translatable("impetus.options.pages.quality"), ImmutableList.copyOf(groups));
    }

    public static OptionPage advanced() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.CPU_SAVING)
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.PERSISTENT_MAPPING.cast())
                        .setName(TextComponent.translatable("impetus.options.use_persistent_mapping.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.use_persistent_mapping.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> {
                            opts.advanced.useAdvancedStagingBuffers = value;
                            RenderRegionManager.USE_ADVANCED_STAGING_BUFFERS = value;
                        }, opts -> opts.advanced.useAdvancedStagingBuffers)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.CPU_FRAMES_AHEAD.cast())
                        .setName(TextComponent.translatable("impetus.options.cpu_render_ahead_limit.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.cpu_render_ahead_limit.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 9, 1, ControlValueFormatter.translateVariable("impetus.options.cpu_render_ahead_limit.value")))
                        .setBinding((opts, value) -> opts.advanced.cpuRenderAheadLimit = value, opts -> opts.advanced.cpuRenderAheadLimit)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.MEMORY_TRACING.cast())
                        .setName(TextComponent.translatable("impetus.options.memory_tracing.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.memory_tracing.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> {
                            opts.advanced.enableMemoryTracing = value;
                            NativeBuffer.ENABLE_MEMORY_TRACING = value;
                        }, opts -> opts.advanced.enableMemoryTracing)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.SHOW_TOASTS.cast())
                        .setName(TextComponent.translatable("impetus.options.show_toasts.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.show_toasts.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.notifications.showToasts = value, opts -> opts.notifications.showToasts)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.INCOMPATIBLE_PACK_WARNINGS.cast())
                        .setName(TextComponent.translatable("impetus.options.incompatible_pack_warnings.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.incompatible_pack_warnings.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.advanced.disableIncompatibleModWarnings = !value, opts -> !opts.advanced.disableIncompatibleModWarnings)
                        .build())
                .build());

        return new OptionPage(StandardOptions.Pages.ADVANCED, TextComponent.translatable("impetus.options.pages.advanced"), ImmutableList.copyOf(groups));
    }

    public static OptionStorage<GameSettings> getVanillaOpts() {
        return vanillaOpts;
    }

    public static OptionStorage<ImpetusGameOptions> getSodiumOpts() {
        return sodiumOpts;
    }

    private static class MenuState {
    }
}
