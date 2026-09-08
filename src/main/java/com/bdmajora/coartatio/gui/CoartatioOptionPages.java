package com.bdmajora.coartatio.gui;

import com.bdmajora.coartatio.CoartatioConfig;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.control.ControlValueFormatter;
import com.bdmajora.impetus.api.options.control.SliderControl;
import com.bdmajora.impetus.api.options.control.TickBoxControl;
import com.bdmajora.impetus.api.options.structure.OptionFlag;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.options.structure.OptionImpact;
import com.bdmajora.impetus.api.options.structure.OptionImpl;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.api.options.structure.OptionStorage;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

/**
 * The Memory page in Impetus' video options, alongside General, Quality, Performance and the Umbra
 * pages.
 *
 * <p>Built the same way {@code UmbraOptionPages} is: an {@link OptionStorage} over the subsystem's own
 * config object, and {@link OptionImpl} bindings that write straight through to it.
 *
 * <h2>Why almost everything says "requires restart"</h2>
 *
 * <p>Coartatio's switches are read once, by {@code CoartatioMixinPlugin}, when it decides which
 * mixins to apply — before the game window exists. Turning one off at runtime cannot un-apply a
 * mixin, and turning one on cannot apply one. Those options are flagged
 * {@link OptionFlag#REQUIRES_GAME_RESTART} so the screen says so rather than appearing to work.
 *
 * <p>The exceptions are the four read live: the two diagnostics, and the two NBT map settings, which
 * are consulted whenever a compound is created and so apply to everything built from that point on.
 */
public final class CoartatioOptionPages {
    private static final String MOD_ID = "coartatio";

    /** Saving on every toggle is fine: the file is seventeen lines and writes are user-paced. */
    private static final OptionStorage<CoartatioConfig> STORAGE = new OptionStorage<CoartatioConfig>() {
        @Override
        public CoartatioConfig getData() {
            return CoartatioConfig.get();
        }

        @Override
        public void save() {
            CoartatioConfig.get().save();
        }
    };

    private CoartatioOptionPages() {
    }

    public static OptionPage memory() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(MOD_ID, "deduplication"))
                .add(restartToggle("deduplicate_resource_locations",
                        "impetus.options.coartatio.resource_locations",
                        OptionImpact.LOW,
                        (config, value) -> config.deduplicateResourceLocations = value,
                        config -> config.deduplicateResourceLocations))
                .add(restartToggle("deduplicate_model_variants",
                        "impetus.options.coartatio.model_variants",
                        OptionImpact.LOW,
                        (config, value) -> config.deduplicateModelVariants = value,
                        config -> config.deduplicateModelVariants))
                .add(restartToggle("pool_quad_vertex_data",
                        "impetus.options.coartatio.quad_vertex_data",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.poolQuadVertexData = value,
                        config -> config.poolQuadVertexData))
                .add(restartToggle("canonicalize_multipart_conditions",
                        "impetus.options.coartatio.multipart_conditions",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.canonicalizeMultipartConditions = value,
                        config -> config.canonicalizeMultipartConditions))
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(MOD_ID, "block_states"))
                .add(restartToggle("optimize_block_states",
                        "impetus.options.coartatio.block_states",
                        OptionImpact.HIGH,
                        (config, value) -> config.optimizeBlockStates = value,
                        config -> config.optimizeBlockStates))
                .add(restartToggle("compact_state_properties",
                        "impetus.options.coartatio.state_properties",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.compactStateProperties = value,
                        config -> config.compactStateProperties))
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(MOD_ID, "collections"))
                .add(restartToggle("compact_nbt_backing_map",
                        "impetus.options.coartatio.nbt_backing_map",
                        OptionImpact.HIGH,
                        (config, value) -> config.compactNbtBackingMap = value,
                        config -> config.compactNbtBackingMap))
                // Read per compound, so this one genuinely applies without a restart.
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setId(OptionIdentifier.create(MOD_ID, "intern_nbt_keys", boolean.class))
                        .setName(TextComponent.translatable("impetus.options.coartatio.nbt_keys.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.coartatio.nbt_keys.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((config, value) -> config.internNbtKeys = value, config -> config.internNbtKeys)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, STORAGE)
                        .setId(OptionIdentifier.create(MOD_ID, "nbt_array_map_threshold", int.class))
                        .setName(TextComponent.translatable("impetus.options.coartatio.nbt_threshold.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.coartatio.nbt_threshold.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 64, 2,
                                ControlValueFormatter.number()))
                        .setBinding((config, value) -> config.nbtArrayMapThreshold = value,
                                config -> config.nbtArrayMapThreshold)
                        .build())
                .add(restartToggle("compact_baked_models",
                        "impetus.options.coartatio.baked_models",
                        OptionImpact.LOW,
                        (config, value) -> config.compactBakedModels = value,
                        config -> config.compactBakedModels))
                .add(restartToggle("compact_model_graph",
                        "impetus.options.coartatio.model_graph",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.compactModelGraph = value,
                        config -> config.compactModelGraph))
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(MOD_ID, "world"))
                .add(restartToggle("strip_chunk_nbt",
                        "impetus.options.coartatio.chunk_nbt",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.stripChunkNbt = value,
                        config -> config.stripChunkNbt))
                .add(restartToggle("drop_empty_chunk_sections",
                        "impetus.options.coartatio.empty_sections",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.dropEmptyChunkSections = value,
                        config -> config.dropEmptyChunkSections))
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(MOD_ID, "system"))
                .add(restartToggle("weaken_class_loader_cache",
                        "impetus.options.coartatio.class_loader_cache",
                        OptionImpact.HIGH,
                        (config, value) -> config.weakenClassLoaderCache = value,
                        config -> config.weakenClassLoaderCache))
                .add(restartToggle("release_sprite_data",
                        "impetus.options.coartatio.sprite_data",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.releaseSpriteData = value,
                        config -> config.releaseSpriteData))
                .add(restartToggle("deduplicate_model_transforms",
                        "impetus.options.coartatio.model_transforms",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.deduplicateModelTransforms = value,
                        config -> config.deduplicateModelTransforms))
                .add(restartToggle("release_bake_state",
                        "impetus.options.coartatio.bake_state",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.releaseBakeState = value,
                        config -> config.releaseBakeState))
                .add(restartToggle("lazy_search_trees",
                        "impetus.options.coartatio.search_trees",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.lazySearchTrees = value,
                        config -> config.lazySearchTrees))
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setId(OptionIdentifier.create(MOD_ID, "clear_pools_on_world_leave", boolean.class))
                        .setName(TextComponent.translatable("impetus.options.coartatio.clear_on_leave.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.coartatio.clear_on_leave.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((config, value) -> config.clearPoolsOnWorldLeave = value,
                                config -> config.clearPoolsOnWorldLeave)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(restartToggle("compact_runtime_collections",
                        "impetus.options.coartatio.runtime_collections",
                        OptionImpact.MEDIUM,
                        (config, value) -> config.compactRuntimeCollections = value,
                        config -> config.compactRuntimeCollections))
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(MOD_ID, "diagnostics"))
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setId(OptionIdentifier.create(MOD_ID, "show_debug_overlay", boolean.class))
                        .setName(TextComponent.translatable("impetus.options.coartatio.debug_overlay.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.coartatio.debug_overlay.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((config, value) -> config.showDebugOverlay = value,
                                config -> config.showDebugOverlay)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setId(OptionIdentifier.create(MOD_ID, "log_statistics", boolean.class))
                        .setName(TextComponent.translatable("impetus.options.coartatio.log_statistics.name"))
                        .setTooltip(TextComponent.translatable("impetus.options.coartatio.log_statistics.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((config, value) -> config.logStatistics = value,
                                config -> config.logStatistics)
                        .build())
                .build());

        return new OptionPage(
                OptionIdentifier.create(MOD_ID, "memory"),
                TextComponent.translatable("impetus.options.pages.memory"),
                ImmutableList.copyOf(groups));
    }

    private static OptionImpl<CoartatioConfig, Boolean> restartToggle(
            String path,
            String langKey,
            OptionImpact impact,
            java.util.function.BiConsumer<CoartatioConfig, Boolean> setter,
            java.util.function.Function<CoartatioConfig, Boolean> getter) {
        return OptionImpl.createBuilder(boolean.class, STORAGE)
                .setId(OptionIdentifier.create(MOD_ID, path, boolean.class))
                .setName(TextComponent.translatable(langKey + ".name"))
                .setTooltip(TextComponent.translatable(langKey + ".tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding(setter, getter)
                .setImpact(impact)
                .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                .build();
    }
}
