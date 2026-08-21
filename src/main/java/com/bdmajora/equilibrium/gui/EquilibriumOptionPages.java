package com.bdmajora.equilibrium.gui;

import com.bdmajora.equilibrium.Equilibrium;
import com.bdmajora.equilibrium.config.EquilibriumConfig;
import com.bdmajora.equilibrium.config.EquilibriumOptions;
import com.bdmajora.impetus.api.options.OptionIdentifier;
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
 * The Optimizations page in Impetus' video options, alongside General, Quality, Performance, Memory,
 * Lighting and the Iris pages.
 *
 * <p>Named apart from the existing Performance page deliberately: that one holds the renderer's own
 * knobs, which trade visual fidelity for frame rate and take effect immediately. Nothing here trades
 * anything — every option is a patch that does the same work faster — and none of it takes effect
 * until the next launch. Putting the two on one page would invite reading them the same way.
 *
 * <p>Built by walking {@link EquilibriumOptions} rather than by listing options by hand, so an option
 * added to the tree appears here without this file being touched. The tree's top-level categories
 * become the page's groups, in declaration order, and each option becomes a tick box.
 *
 * <p>Every switch on this page carries {@link OptionFlag#REQUIRES_GAME_RESTART}, and that is not a
 * hedge. These options are read by {@code EquilibriumMixinPlugin} during coremod setup, before the
 * game window exists — by the time this screen can be opened, the decision each one governs has
 * already been made and the bytecode either was or was not rewritten. Turning one off at runtime can
 * only change what happens next launch, and a screen that implied otherwise would be lying.
 *
 * <p>Impact badges are assigned per category rather than per option, because the honest granularity
 * is coarser than the option tree: what a given optimization is worth depends entirely on what the
 * world is doing. The explosion patches are worth nothing until something explodes, and then they are
 * worth a great deal.
 */
public final class EquilibriumOptionPages {
    private static final String MOD_ID = "equilibrium";

    private static final OptionStorage<EquilibriumConfig> STORAGE = new OptionStorage<EquilibriumConfig>() {
        @Override
        public EquilibriumConfig getData() {
            return Equilibrium.config();
        }

        @Override
        public void save() {
            Equilibrium.config().save();
        }
    };

    private EquilibriumOptionPages() {
    }

    public static OptionPage optimizations() {
        List<OptionGroup> groups = new ArrayList<>();

        for (String category : EquilibriumOptions.categories()) {
            // FIX: Prefix the category string with "group." so it doesn't collide with boolean toggles
            OptionGroup.Builder group = OptionGroup.createBuilder()
                    .setId(OptionIdentifier.create(MOD_ID, "group." + category));

            for (EquilibriumOptions.Entry entry : EquilibriumOptions.inCategory(category)) {
                group.add(toggle(entry));
            }

            groups.add(group.build());
        }

        return new OptionPage(
                OptionIdentifier.create(MOD_ID, "optimizations"),
                TextComponent.translatable("impetus.options.pages.optimizations"),
                ImmutableList.copyOf(groups));
        }

    private static OptionImpl<EquilibriumConfig, Boolean> toggle(EquilibriumOptions.Entry entry) {
        String key = langKey(entry);

        OptionImpl.Builder<EquilibriumConfig, Boolean> builder =
                OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setId(OptionIdentifier.create(MOD_ID, key(entry), boolean.class))
                        .setName(TextComponent.translatable(key + ".name"))
                        .setTooltip(TextComponent.translatable(key + ".tooltip"))
                        .setControl(TickBoxControl::new)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setBinding(
                                (config, value) -> config.setOptionEnabled(entry.name(), value),
                                config -> config.isOptionEnabled(entry.name()));

        OptionImpact impact = impactOf(entry);

        if (impact != null) {
            builder.setImpact(impact);
        }

        return builder.build();
    }

    /**
     * How much the category is worth, stated at the granularity the answer is actually knowable at.
     *
     * <p>The utility categories get no badge: they enable other options rather than removing work
     * themselves, and a badge would suggest turning one on is worth something on its own.
     */
    private static OptionImpact impactOf(EquilibriumOptions.Entry entry) {
        switch (entry.category()) {
            case "world":
                // Explosions, ray casting and block access. The largest single wins on this version.
                return OptionImpact.HIGH;
            case "entity":
            case "math":
                return OptionImpact.MEDIUM;
            case "ai":
            case "alloc":
            case "block":
            case "chunk":
                return OptionImpact.LOW;
            default:
                return null;
        }
    }

    /** {@code mixin.alloc.enum_values.piston_block} → {@code alloc_enum_values_piston_block}. */
    private static String key(EquilibriumOptions.Entry entry) {
        return entry.path().replace('.', '_');
    }

    private static String langKey(EquilibriumOptions.Entry entry) {
        return "impetus.options.equilibrium." + key(entry);
    }
}
