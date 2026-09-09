package com.bdmajora.impetus.umbra.gui.modern;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.control.ActionButtonControl;
import com.bdmajora.impetus.api.options.control.CyclingControl;
import com.bdmajora.impetus.api.options.control.ReadOnlyStringControl;
import com.bdmajora.impetus.umbra.pipeline.ColorSpaceConverter;
import com.bdmajora.impetus.api.options.structure.OptionFlag;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.options.structure.OptionImpl;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.api.options.structure.OptionStorage;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.shaderpack.ShaderPack;

import java.util.OptionalInt;

// Builds Umbra's page in Impetus' video options
public final class UmbraOptionPages {
    // Groups the page under the Umbra sidebar heading and picks its accent colour
    private static final String UMBRA_MOD_ID = "umbra";
    // Every option here reads and writes its own subsystem directly, so the storage object holds no state and
    // exists only because OptionImpl requires one; save() is deliberately not implemented for the same reason
    private static final UmbraMenuState STATE = new UmbraMenuState();
    private static final OptionStorage<UmbraMenuState> STORAGE = () -> STATE;

    private UmbraOptionPages() {
    }

    // The whole Umbra tab: the pack picker and the pack-level settings, on one page
    // These used to be two sidebar entries with one group each, and the split earned nothing. The Umbra sidebar
    // heading already names the section, TabFrame was stacking both pages into one scroll pane anyway, and the
    // "Settings" page opened with a second button that displayed the very same ShaderPackSelectScreen as the
    // first page's — so the duplicate is dropped here and what is left fits one page
    // Kept as two groups rather than one flat list: OptionPageFrame draws no group header, but it does put a
    // gap between groups, which keeps "pick a pack" visually apart from "what the pack decided"
    public static OptionPage shaderPacks(GuiScreen parent) {
        // The pack picker on its own, so it reads as the primary action rather than one row among the settings
        OptionGroup packs = OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(UMBRA_MOD_ID, "shader_packs"))
                .add(openAction(
                        "open_shader_packs",
                        TextComponent.translatable("options.umbra.shaderPackSelection"),
                        TextComponent.translatable("options.umbra.shaderPackSelection.tooltip"),
                        () -> Minecraft.getMinecraft().displayGuiScreen(new ShaderPackSelectScreen(parent)),
                        true))
                .build();

        // Everything that describes the loaded pack rather than which pack is loaded
        OptionGroup settings = OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(UMBRA_MOD_ID, "settings"))
                .add(OptionImpl.createBuilder(ColorSpaceConverter.ColorSpace.class, STORAGE)
                        .setId(OptionIdentifier.create(UMBRA_MOD_ID, "color_space", ColorSpaceConverter.ColorSpace.class))
                        .setName(TextComponent.translatable("options.umbra.colorSpace"))
                        .setTooltip(TextComponent.translatable("options.umbra.colorSpace.tooltip"))
                        // Display names are supplied literally because the enum constant names are not what a
                        // user reads on a monitor spec sheet, and the array order has to track the enum's
                        .setControl(option -> new CyclingControl<>(option,
                                ColorSpaceConverter.ColorSpace.values(),
                                new TextComponent[] {
                                        TextComponent.literal("sRGB"),
                                        TextComponent.literal("DCI-P3"),
                                        TextComponent.literal("Display P3"),
                                        TextComponent.literal("Rec.2020"),
                                        TextComponent.literal("Adobe RGB") }))
                        // The converter is the source of truth, not STATE, so the getter reads back from it
                        // The config save is best-effort: a failed write costs the setting on next launch, and
                        // must not take the options screen down with it
                        .setBinding((state, value) -> {
                            ColorSpaceConverter.setColorSpace(value);
                            try {
                                Umbra.getConfig().save();
                            } catch (Exception ignored) {
                            }
                        }, state -> ColorSpaceConverter.getColorSpace())
                        .build())
                // Read-only: the loaded pack owns this number, so the row reports it rather than offering it
                // The no-op setter is what makes that explicit; setEnabled(false) is what greys it out
                .add(OptionImpl.createBuilder(String.class, STORAGE)
                        .setId(OptionIdentifier.create(UMBRA_MOD_ID, "max_shadow_distance", String.class))
                        .setName(TextComponent.translatable("options.umbra.maxShadowDistance"))
                        .setTooltip(TextComponent.translatable("options.umbra.maxShadowDistance.tooltip"))
                        .setControl(ReadOnlyStringControl::new)
                        .setBinding((state, value) -> { }, state -> shadowDistanceLabel())
                        .setEnabled(false)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build();

        return new OptionPage(
                OptionIdentifier.create(UMBRA_MOD_ID, "shader_packs"),
                TextComponent.translatable("options.umbra.shaderPackSelection"),
                ImmutableList.of(packs, settings));
    }

    // A row whose "control" is a button that runs an action instead of editing a value
    // The boolean type parameter is a placeholder: OptionImpl needs a value type, nothing here has one, so the
    // binding reads a constant false and writes nothing
    private static OptionImpl<UmbraMenuState, Boolean> openAction(String path, TextComponent name, TextComponent tooltip,
                                                                Runnable action, boolean enabled) {
        return OptionImpl.createBuilder(boolean.class, STORAGE)
                .setId(OptionIdentifier.create(UMBRA_MOD_ID, path, boolean.class))
                .setName(name)
                .setTooltip(tooltip)
                .setControl(option -> new ActionButtonControl(option, action))
                .setBinding((state, value) -> { }, state -> false)
                .setEnabled(enabled)
                .build();
    }

    // What the read-only shadow distance row shows: the pack's own shadowDistance when it declares one,
    // otherwise "Default" — which also covers the no-pack-loaded case
    private static String shadowDistanceLabel() {
        ShaderPack pack = Umbra.getCurrentPack();
        if (pack == null) {
            return "Default";
        }

        OptionalInt distance = pack.getProperties().getShadowDistance();
        return distance.isPresent() ? distance.getAsInt() + " blocks" : "Default";
    }

    private static class UmbraMenuState {
        String colorSpace() {
            return "sRGB";
        }
    }
}
