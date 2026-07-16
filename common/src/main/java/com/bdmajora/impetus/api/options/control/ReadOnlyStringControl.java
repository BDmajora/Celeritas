package com.bdmajora.impetus.api.options.control;

import com.bdmajora.impetus.api.options.structure.Option;
import com.bdmajora.impetus.engine.impl.gui.framework.DrawContext;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.util.Dim2i;

public class ReadOnlyStringControl implements Control<String> {
    private final Option<String> option;

    public ReadOnlyStringControl(Option<String> option) {
        this.option = option;
    }

    @Override
    public Option<String> getOption() {
        return this.option;
    }

    @Override
    public ControlElement<String> createElement(Dim2i dim) {
        return new Element(this.option, dim);
    }

    @Override
    public int getMaxWidth() {
        return 90;
    }

    private static class Element extends ControlElement<String> {
        Element(Option<String> option, Dim2i dim) {
            super(option, dim);
        }

        @Override
        public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
            super.render(drawContext, mouseX, mouseY, delta);

            TextComponent label = TextComponent.literal(this.option.getValue());
            boolean enabled = this.option.isAvailable();
            if (!enabled) {
                label = this.formatDisabledControlValue(label);
            }

            int width = drawContext.getStringWidth(label);
            drawContext.drawString(label, this.dim.getLimitX() - width - 6, this.dim.getCenterY() - 4,
                    enabled ? 0xFFFFFFFF : this.getDisabledControlColor());
        }
    }
}
