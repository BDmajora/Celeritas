package com.bdmajora.impetus.api.options.control;

import com.bdmajora.impetus.api.options.structure.Option;
import com.bdmajora.impetus.engine.impl.gui.framework.DrawContext;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.gui.framework.TextFormattingStyle;
import com.bdmajora.impetus.engine.impl.gui.theme.DefaultColors;
import com.bdmajora.impetus.engine.impl.gui.widgets.AbstractWidget;
import com.bdmajora.impetus.engine.impl.gui.widgets.FlatButtonWidget;
import com.bdmajora.impetus.engine.impl.util.Dim2i;

import org.jetbrains.annotations.NotNull;

public class ControlElement<T> extends AbstractWidget implements OptionControlElement<T> {
    protected final Option<T> option;

    protected final Dim2i dim;

    private @NotNull FlatButtonWidget.Style style = FlatButtonWidget.Style.defaults();

    public ControlElement(Option<T> option, Dim2i dim) {
        this.option = option;
        this.dim = dim;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        String name = drawContext.extractString(this.option.getName());
        TextComponent label;

        boolean hovered = this.isMouseOver(mouseX, mouseY);
        int availableLabelWidth = Math.max(0, this.dim.width() - this.option.getControl().getMaxWidth() - 12);

        if (drawContext.getStringWidth(name) > availableLabelWidth) {
            int ellipsisWidth = drawContext.getStringWidth("...");
            name = drawContext.substrByWidth(name, Math.max(0, availableLabelWidth - ellipsisWidth)) + "...";
        }

        if (this.option.isAvailable()) {
            if (this.option.hasChanged()) {
                label = TextComponent.literal(name + " *").withStyle(TextFormattingStyle.ITALIC);
            } else {
                label = TextComponent.literal(name).withStyle(TextFormattingStyle.WHITE);
            }
        } else {
            label = this.formatDisabledControlValue(TextComponent.literal(name));
        }

        drawContext.fill(this.dim.x(), this.dim.y(), this.dim.getLimitX(), this.dim.getLimitY(), hovered ? style.bgHovered : style.bgDefault);
        drawContext.drawString(label, this.dim.x() + 6, this.dim.getCenterY() - 4, style.textDefault);
    }

    public Option<T> getOption() {
        return this.option;
    }

    public Dim2i getDimensions() {
        return this.dim;
    }

    protected int getAccentColor(DrawContext drawContext) {
        var id = this.option.getId();
        return drawContext.getModAccentColor(id != null ? id.getModId() : null);
    }

    protected int getDisabledControlColor() {
        return DefaultColors.TEXT_DISABLED;
    }

    protected TextComponent formatDisabledControlValue(TextComponent value) {
        return value.withStyle(TextFormattingStyle.GRAY, TextFormattingStyle.STRIKETHROUGH);
    }

    @Override
    public boolean isMouseOver(double x, double y) {
        return this.dim.containsCursor(x, y);
    }
}
