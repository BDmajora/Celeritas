package com.bdmajora.impetus.engine.impl.gui.frame.tab;

import com.bdmajora.impetus.engine.impl.gui.framework.DrawContext;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.gui.theme.DefaultColors;
import com.bdmajora.impetus.engine.impl.gui.widgets.FlatButtonWidget;
import com.bdmajora.impetus.engine.impl.util.Dim2i;

import java.util.Objects;

// Sidebar header for a mod's group of pages: icon, name, and version (Umbra/Sodium style layout)
public class TabHeaderWidget extends FlatButtonWidget {
    private static final String FALLBACK_TEXTURE = "textures/misc/unknown_pack.png";
    private static final int ICON_SIZE = 20;
    private static final int ICON_PADDING = 5;

    // Row height used by the sidebar when laying this widget out
    public static final int HEIGHT = 30;

    private final String modId;

    public TabHeaderWidget(Dim2i dim, String modId) {
        super(dim, TextComponent.literal(""), () -> {});
        this.modId = modId;
    }

    @Override
    protected boolean isHovered(int mouseX, int mouseY) {
        return false;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        String icon = Objects.requireNonNullElse(drawContext.getModLogoPath(this.modId), FALLBACK_TEXTURE);
        int iconY = this.dim.getCenterY() - (ICON_SIZE / 2);
        drawContext.blitWholeImage(icon, this.dim.x() + ICON_PADDING, iconY, ICON_SIZE, ICON_SIZE);

        int textX = this.dim.x() + ICON_PADDING + ICON_SIZE + ICON_PADDING;
        var name = drawContext.getFriendlyModName(this.modId);
        var version = drawContext.getModVersion(this.modId);
        int accentColor = drawContext.getModAccentColor(this.modId);

        if (version != null) {
            int lineGap = 2;
            int blockHeight = drawContext.lineHeight() * 2 + lineGap;
            int nameY = this.dim.getCenterY() - (blockHeight / 2);

            drawContext.drawString(name, textX, nameY, accentColor);
            drawContext.drawString(version, textX, nameY + drawContext.lineHeight() + lineGap, DefaultColors.withAlpha(accentColor, 0xB8));
        } else {
            drawContext.drawString(name, textX, this.dim.getCenterY() - (drawContext.lineHeight() / 2), accentColor);
        }
    }
}
