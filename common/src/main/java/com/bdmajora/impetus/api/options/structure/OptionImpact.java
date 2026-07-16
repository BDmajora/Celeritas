package com.bdmajora.impetus.api.options.structure;

import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.gui.framework.TextFormattingStyle;
import com.bdmajora.impetus.engine.impl.gui.options.TextProvider;

public enum OptionImpact implements TextProvider {
    LOW(TextFormattingStyle.GREEN, "impetus.option_impact.low"),
    MEDIUM(TextFormattingStyle.YELLOW, "impetus.option_impact.medium"),
    HIGH(TextFormattingStyle.GOLD, "impetus.option_impact.high"),
    VARIES(TextFormattingStyle.WHITE, "impetus.option_impact.varies");

    private final TextComponent text;

    OptionImpact(TextFormattingStyle color, String text) {
        this.text = TextComponent.translatable(text).withStyle(color);
    }

    @Override
    public TextComponent getLocalizedName() {
        return this.text;
    }
}
