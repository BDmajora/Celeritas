package com.bdmajora.impetus.iris.gui.modern;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import com.bdmajora.impetus.api.options.OptionIdentifier;
import com.bdmajora.impetus.api.options.control.ActionButtonControl;
import com.bdmajora.impetus.api.options.control.CyclingControl;
import com.bdmajora.impetus.api.options.control.ReadOnlyStringControl;
import com.bdmajora.impetus.iris.pipeline.ColorSpaceConverter;
import com.bdmajora.impetus.api.options.structure.OptionFlag;
import com.bdmajora.impetus.api.options.structure.OptionGroup;
import com.bdmajora.impetus.api.options.structure.OptionImpl;
import com.bdmajora.impetus.api.options.structure.OptionPage;
import com.bdmajora.impetus.api.options.structure.OptionStorage;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;

import java.util.OptionalInt;

public final class IrisOptionPages {
    private static final String IRIS_MOD_ID = "iris";
    private static final IrisMenuState STATE = new IrisMenuState();
    private static final OptionStorage<IrisMenuState> STORAGE = () -> STATE;

    private IrisOptionPages() {
    }

    public static OptionPage shaderPacks(GuiScreen parent) {
        return new OptionPage(
                OptionIdentifier.create(IRIS_MOD_ID, "shader_packs"),
                TextComponent.translatable("options.iris.shaderPackSelection"),
                ImmutableList.of(OptionGroup.createBuilder()
                        .setId(OptionIdentifier.create(IRIS_MOD_ID, "shader_packs"))
                        .add(openAction(
                                "open_shader_packs",
                                TextComponent.translatable("options.iris.shaderPackSelection"),
                                TextComponent.translatable("options.iris.shaderPackSelection.tooltip"),
                                () -> Minecraft.getMinecraft().displayGuiScreen(new ShaderPackSelectScreen(parent)),
                                true))
                        .build()));
    }

    public static OptionPage settings(GuiScreen parent) {
        return new OptionPage(
                OptionIdentifier.create(IRIS_MOD_ID, "settings"),
                TextComponent.translatable("options.iris.settings"),
                ImmutableList.of(OptionGroup.createBuilder()
                        .setId(OptionIdentifier.create(IRIS_MOD_ID, "settings"))
                        .add(openAction(
                                "shader_pack_list",
                                TextComponent.translatable("options.iris.shaderPackList"),
                                TextComponent.translatable("options.iris.shaderPackList.tooltip"),
                                () -> Minecraft.getMinecraft().displayGuiScreen(new ShaderPackSelectScreen(parent)),
                                true))
                        .add(OptionImpl.createBuilder(ColorSpaceConverter.ColorSpace.class, STORAGE)
                                .setId(OptionIdentifier.create(IRIS_MOD_ID, "color_space", ColorSpaceConverter.ColorSpace.class))
                                .setName(TextComponent.translatable("options.iris.colorSpace"))
                                .setTooltip(TextComponent.translatable("options.iris.colorSpace.tooltip"))
                                .setControl(option -> new CyclingControl<>(option,
                                        ColorSpaceConverter.ColorSpace.values(),
                                        new TextComponent[] {
                                                TextComponent.literal("sRGB"),
                                                TextComponent.literal("DCI-P3"),
                                                TextComponent.literal("Display P3"),
                                                TextComponent.literal("Rec.2020"),
                                                TextComponent.literal("Adobe RGB") }))
                                .setBinding((state, value) -> {
                                    ColorSpaceConverter.setColorSpace(value);
                                    try {
                                        Iris.getConfig().save();
                                    } catch (Exception ignored) {
                                    }
                                }, state -> ColorSpaceConverter.getColorSpace())
                                .build())
                        .add(OptionImpl.createBuilder(String.class, STORAGE)
                                .setId(OptionIdentifier.create(IRIS_MOD_ID, "max_shadow_distance", String.class))
                                .setName(TextComponent.translatable("options.iris.maxShadowDistance"))
                                .setTooltip(TextComponent.translatable("options.iris.maxShadowDistance.tooltip"))
                                .setControl(ReadOnlyStringControl::new)
                                .setBinding((state, value) -> { }, state -> shadowDistanceLabel())
                                .setEnabled(false)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                .build())
                        .build()));
    }

    private static OptionImpl<IrisMenuState, Boolean> openAction(String path, TextComponent name, TextComponent tooltip,
                                                                Runnable action, boolean enabled) {
        return OptionImpl.createBuilder(boolean.class, STORAGE)
                .setId(OptionIdentifier.create(IRIS_MOD_ID, path, boolean.class))
                .setName(name)
                .setTooltip(tooltip)
                .setControl(option -> new ActionButtonControl(option, action))
                .setBinding((state, value) -> { }, state -> false)
                .setEnabled(enabled)
                .build();
    }

    private static String shadowDistanceLabel() {
        ShaderPack pack = Iris.getCurrentPack();
        if (pack == null) {
            return "Default";
        }

        OptionalInt distance = pack.getProperties().getShadowDistance();
        return distance.isPresent() ? distance.getAsInt() + " blocks" : "Default";
    }

    private static class IrisMenuState {
        String colorSpace() {
            return "sRGB";
        }
    }
}
