package com.bdmajora.impetus.api.options.control;

import com.bdmajora.impetus.api.options.structure.Option;
import com.bdmajora.impetus.engine.impl.gui.framework.DrawContext;
import com.bdmajora.impetus.engine.impl.gui.framework.InteractionContext;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.gui.framework.TextFormattingStyle;
import com.bdmajora.impetus.engine.impl.util.Dim2i;

import java.util.Objects;

public class ActionButtonControl implements Control<Boolean> {
    private static final TextComponent DEFAULT_LABEL = TextComponent.translatable("impetus.options.buttons.open");

    private final Option<Boolean> option;
    private final TextComponent actionLabel;
    private final Runnable action;

    public ActionButtonControl(Option<Boolean> option, Runnable action) {
        this(option, DEFAULT_LABEL, action);
    }

    public ActionButtonControl(Option<Boolean> option, TextComponent actionLabel, Runnable action) {
        this.option = Objects.requireNonNull(option, "Option must not be null");
        this.actionLabel = Objects.requireNonNull(actionLabel, "Action label must not be null");
        this.action = Objects.requireNonNull(action, "Action must not be null");
    }

    // A dummy option; the button has no value
    @Override
    public Option<Boolean> getOption() {
        return this.option;
    }

    // The button widget
    @Override
    public ControlElement<Boolean> createElement(Dim2i dim) {
        return new Button(this.option, dim, this.actionLabel, this.action);
    }

    // Label width plus padding
    @Override
    public int getMaxWidth() {
        return 70;
    }

    private static class Button extends ControlElement<Boolean> {
        private final TextComponent actionLabel;
        private final Runnable action;

        Button(Option<Boolean> option, Dim2i dim, TextComponent actionLabel, Runnable action) {
            super(option, dim);
            this.actionLabel = actionLabel;
            this.action = action;
        }

        // Button with its label
        @Override
        public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
            super.render(drawContext, mouseX, mouseY, delta);

            TextComponent label = this.actionLabel.withStyle(TextFormattingStyle.UNDERLINE);
            boolean enabled = this.option.isAvailable();
            if (!enabled) {
                label = this.formatDisabledControlValue(label);
            }

            int color = enabled ? this.getAccentColor(drawContext) : this.getDisabledControlColor();
            int width = drawContext.getStringWidth(label);
            drawContext.drawString(label, this.dim.getLimitX() - width - 18, this.dim.getCenterY() - 4, color);
            drawContext.drawString(">", this.dim.getLimitX() - 10, this.dim.getCenterY() - 4, color);
        }

        // Runs the action
        @Override
        public boolean mouseClicked(InteractionContext context, double mouseX, double mouseY, int button) {
            if (this.option.isAvailable() && button == 0 && this.dim.containsCursor(mouseX, mouseY)) {
                this.action.run();
                context.playClickSound();
                return true;
            }

            return false;
        }
    }
}
