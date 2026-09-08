package com.bdmajora.impetus.umbra.gui.modern;

import com.bdmajora.impetus.engine.impl.gui.framework.DrawContext;
import com.bdmajora.impetus.engine.impl.gui.framework.InteractionContext;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.gui.widgets.AbstractWidget;
import com.bdmajora.impetus.engine.impl.util.Dim2i;

import java.util.List;

/**
 * A single element on a shader-option screen, drawn as a flat Sodium/Umbra-style tile. Renders a left-aligned label and
 * an optional right-aligned, colored value (options), or a single centered label (sub-screen links). Left-click runs
 * the primary action (cycle forward / open); right-click runs the secondary action (cycle backward), matching OptiFine.
 * <p>
 * The owning screen rebuilds these widgets whenever a value changes, so the label/value are captured at build time.
 */
public class OptionButtonWidget extends AbstractWidget {
    private static final int BG_DEFAULT = 0x40101010;
    private static final int BG_HOVERED = 0x90101010;
    private static final int BG_DISABLED = 0x20101010;
    private static final int BORDER_DEFAULT = 0xFF404040;
    private static final int BORDER_HOVERED = 0xFF9A9A9A;
    private static final int TEXT_DEFAULT = 0xFFE0E0E0;
    private static final int TEXT_DISABLED = 0xFF707070;

    private final Dim2i dim;
    private final TextComponent label;
    private final String value;
    private final int valueColor;
    private final boolean centered;
    private final boolean enabled;
    private final Runnable onLeft;
    private final Runnable onRight;
    private final List<String> tooltip;

    public OptionButtonWidget(Dim2i dim, TextComponent label, String value, int valueColor, boolean centered,
                              boolean enabled, Runnable onLeft, Runnable onRight, List<String> tooltip) {
        this.dim = dim;
        this.label = label;
        this.value = value;
        this.valueColor = valueColor;
        this.centered = centered;
        this.enabled = enabled;
        this.onLeft = onLeft;
        this.onRight = onRight;
        this.tooltip = tooltip;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        boolean hovered = this.enabled && this.dim.containsCursor(mouseX, mouseY);

        drawContext.fill(this.dim.x(), this.dim.y(), this.dim.getLimitX(), this.dim.getLimitY(),
                this.enabled ? (hovered ? BG_HOVERED : BG_DEFAULT) : BG_DISABLED);
        drawContext.drawBorder(this.dim.x(), this.dim.y(), this.dim.getLimitX(), this.dim.getLimitY(),
                hovered ? BORDER_HOVERED : BORDER_DEFAULT);

        int textColor = this.enabled ? TEXT_DEFAULT : TEXT_DISABLED;
        int textY = this.dim.getCenterY() - 4;

        if (this.centered || this.value == null) {
            int width = drawContext.getStringWidth(this.label);
            drawContext.drawString(this.label, this.dim.getCenterX() - width / 2, textY, textColor);
        } else {
            drawContext.drawString(this.label, this.dim.x() + 8, textY, textColor);
            int valueWidth = drawContext.getStringWidth(TextComponent.literal(this.value));
            drawContext.drawString(TextComponent.literal(this.value), this.dim.getLimitX() - 8 - valueWidth, textY,
                    this.enabled ? this.valueColor : TEXT_DISABLED);
        }
    }

    @Override
    public boolean mouseClicked(InteractionContext context, double mouseX, double mouseY, int button) {
        if (!this.enabled || !this.dim.containsCursor(mouseX, mouseY)) {
            return false;
        }
        if (button == 0 && this.onLeft != null) {
            this.onLeft.run();
            context.playClickSound();
            return true;
        }
        if (button == 1 && this.onRight != null) {
            this.onRight.run();
            context.playClickSound();
            return true;
        }
        return false;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return this.dim.containsCursor(mouseX, mouseY);
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public List<String> getTooltip() {
        return this.tooltip;
    }

    public Dim2i getDim() {
        return this.dim;
    }
}
