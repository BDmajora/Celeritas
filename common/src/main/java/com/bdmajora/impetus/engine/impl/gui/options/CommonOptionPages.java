package com.bdmajora.impetus.engine.impl.gui.options;

import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.control.ControlValueFormatter;
import com.bdmajora.impetus.api.options.control.CyclingControl;
import com.bdmajora.impetus.api.options.control.ReadOnlyStringControl;
import com.bdmajora.impetus.api.options.control.SliderControl;
import com.bdmajora.impetus.api.options.control.TickBoxControl;
import com.bdmajora.impetus.engine.impl.gui.ImpetusGameOptions;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.render.ShaderModBridge;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.executor.ChunkBuilder;
import com.bdmajora.impetus.engine.impl.render.chunk.occlusion.AsyncOcclusionMode;
import com.bdmajora.impetus.api.options.structure.*;

import java.util.ArrayList;
import java.util.List;

public class CommonOptionPages {
    private static final ReadOnlyState readOnlyState = new ReadOnlyState();
    private static final OptionStorage<ReadOnlyState> readOnlyOpts = () -> readOnlyState;

    private static OptionImpl<ReadOnlyState, String> readOnlyOption(OptionIdentifier<Void> id, TextComponent name,
                                                                    TextComponent tooltip, String value) {
        return readOnlyOption(id, name, tooltip, value, false);
    }

    private static OptionImpl<ReadOnlyState, String> displayOnlyOption(OptionIdentifier<Void> id, TextComponent name,
                                                                       TextComponent tooltip, String value) {
        return readOnlyOption(id, name, tooltip, value, true);
    }

    private static OptionImpl<ReadOnlyState, String> readOnlyOption(OptionIdentifier<Void> id, TextComponent name,
                                                                    TextComponent tooltip, String value, boolean enabled) {
        return OptionImpl.createBuilder(String.class, readOnlyOpts)
                .setId(id.cast())
                .setName(name)
                .setTooltip(tooltip)
                .setControl(ReadOnlyStringControl::new)
                .setBinding((state, nextValue) -> { }, state -> value)
                .setEnabled(enabled)
                .build();
    }

    public static OptionGroup sortingGroup(ImpetusGameOptions gameOpts) {
        return OptionGroup.createBuilder()
                .setId(StandardOptions.Group.SORTING)
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.TRANSLUCENT_FACE_SORTING.cast())
                        .setName(TextComponent.translatable("impetus.options.translucent_face_sorting.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.translucent_face_sorting.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.VARIES)
                        .setBinding((opts, value) -> opts.performance.useTranslucentFaceSorting = value, opts -> opts.performance.useTranslucentFaceSorting)
                        .setEnabled(!ShaderModBridge.isNvidiumEnabled())
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, gameOpts)
                        .setId(StandardOptions.Option.CHUNK_FADE_IN_DURATION.cast())
                        .setName(TextComponent.translatable("impetus.options.chunk_fade_in_duration.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.chunk_fade_in_duration.tooltip"))
                        .setControl(o -> new SliderControl(o, 0, 2000, 100, ControlValueFormatter.translateVariable("impetus.options.chunk_fade_in_duration.value")))
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.quality.chunkFadeInDuration = value, opts -> opts.quality.chunkFadeInDuration)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build();
    }

    public static OptionPage performance(ImpetusGameOptions gameOpts) {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.CHUNK_UPDATES)
                .add(OptionImpl.createBuilder(int.class, gameOpts)
                        .setId(StandardOptions.Option.CHUNK_UPDATE_THREADS.cast())
                        .setName(TextComponent.translatable("impetus.options.chunk_update_threads.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.chunk_update_threads.tooltip"))
                        .setControl(o -> new SliderControl(o, 0, ChunkBuilder.getMaxThreadCount(), 1, ControlValueFormatter.quantityOrDisabled("Threads", "Default")))
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.chunkBuilderThreads = value, opts -> opts.performance.chunkBuilderThreads)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.DEFFER_CHUNK_UPDATES.cast())
                        .setName(TextComponent.translatable("impetus.options.chunk_updates.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.always_defer_chunk_updates.tooltip"))
                        .setControl(o -> new CyclingControl<>(o, new Boolean[] { false, true }, new TextComponent[] {
                                TextComponent.literal("Immediate"),
                                TextComponent.literal("Deferred") }))
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.alwaysDeferChunkUpdates = value, opts -> opts.performance.alwaysDeferChunkUpdates)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build())
                .add(OptionImpl.createBuilder(AsyncOcclusionMode.class, gameOpts)
                        .setId(StandardOptions.Option.ASYNC_GRAPH_SEARCH.cast())
                        .setName(TextComponent.translatable("impetus.options.async_graph_search.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.async_graph_search.tooltip"))
                        .setControl(o -> new CyclingControl<>(o, AsyncOcclusionMode.class, new TextComponent[] { TextComponent.literal("Off"), TextComponent.literal("Only Shadows"), TextComponent.literal("Everything") }))
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.asyncOcclusionMode = value, opts -> opts.performance.asyncOcclusionMode)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build()
        );

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.RENDERING_CULLING)
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.BLOCK_FACE_CULLING.cast())
                        .setName(TextComponent.translatable("impetus.options.use_block_face_culling.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.use_block_face_culling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.useBlockFaceCulling = value, opts -> opts.performance.useBlockFaceCulling)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.FOG_OCCLUSION.cast())
                        .setName(TextComponent.translatable("impetus.options.use_fog_occlusion.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.use_fog_occlusion.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.performance.useFogOcclusion = value, opts -> opts.performance.useFogOcclusion)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.ENTITY_CULLING.cast())
                        .setName(TextComponent.translatable("impetus.options.use_entity_culling.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.use_entity_culling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.useEntityCulling = value, opts -> opts.performance.useEntityCulling)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.ANIMATE_VISIBLE_TEXTURES.cast())
                        .setName(TextComponent.translatable("impetus.options.animate_only_visible_textures.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.animate_only_visible_textures.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.animateOnlyVisibleTextures = value, opts -> opts.performance.animateOnlyVisibleTextures)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build()
                )
                .add(readOnlyOption(
                        StandardOptions.Option.NO_ERROR_CONTEXT,
                        TextComponent.translatable("impetus.options.use_no_error_context.name"),
                        TextComponent.translatable("impetus.options.use_no_error_context.disabled_tooltip"),
                        ""))
                .add(displayOnlyOption(
                        StandardOptions.Option.INACTIVITY_FPS_LIMIT,
                        TextComponent.translatable("impetus.options.inactivity_fps_limit.name"),
                        TextComponent.translatable("impetus.options.inactivity_fps_limit.disabled_tooltip"),
                        "AFK"))
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.TRANSLUCENT_FACE_SORTING.cast())
                        .setName(TextComponent.translatable("impetus.options.block_transparency.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.translucent_face_sorting.tooltip"))
                        .setControl(o -> new CyclingControl<>(o, new Boolean[] { false, true }, new TextComponent[] {
                                TextComponent.literal("Fast"),
                                TextComponent.literal("Safe") }))
                        .setImpact(OptionImpact.VARIES)
                        .setBinding((opts, value) -> opts.performance.useTranslucentFaceSorting = value, opts -> opts.performance.useTranslucentFaceSorting)
                        .setEnabled(!ShaderModBridge.isNvidiumEnabled())
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.RENDER_PASS_OPTIMIZATION.cast())
                        .setName(TextComponent.translatable("impetus.options.use_render_pass_optimization.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.use_render_pass_optimization.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.performance.useRenderPassOptimization = value, opts -> opts.performance.useRenderPassOptimization)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.COMPACT_VERTEX_FORMAT.cast())
                        .setName(TextComponent.translatable("impetus.options.use_compact_vertex_format.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.use_compact_vertex_format.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> {
                            opts.performance.useCompactVertexFormat = value;
                        }, opts -> opts.performance.useCompactVertexFormat)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.RENDER_PASS_CONSOLIDATION.cast())
                        .setName(TextComponent.translatable("impetus.options.use_render_pass_consolidation.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.use_render_pass_consolidation.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.performance.useRenderPassConsolidation = value, opts -> opts.performance.useRenderPassConsolidation)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                //? if <1.21.2 {
                .add(OptionImpl.createBuilder(boolean.class, gameOpts)
                        .setId(StandardOptions.Option.USE_FASTER_CLOUDS.cast())
                        .setName(TextComponent.translatable("impetus.options.use_faster_clouds.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.use_faster_clouds.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.performance.useFasterClouds = value, opts -> opts.performance.useFasterClouds)
                        .build())
                //?}
                .build());

        return new OptionPage(StandardOptions.Pages.PERFORMANCE, TextComponent.translatable("impetus.options.pages.performance"), List.copyOf(groups));
    }

    private static class ReadOnlyState {
    }
}
