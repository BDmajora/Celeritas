package com.bdmajora.impetus.engine.impl.gui.frame.tab;

import com.bdmajora.impetus.engine.impl.gui.framework.DrawContext;
import com.bdmajora.impetus.engine.impl.gui.framework.TextComponent;
import com.bdmajora.impetus.engine.impl.gui.widgets.FlatButtonWidget;
import com.bdmajora.impetus.engine.impl.util.Dim2i;

import java.util.Objects;

// Sidebar header for a mod's group of pages: icon and name on one line
// The version used to render as a second, dimmer line under the name; it was dropped because the sidebar is a
// navigation list and a version string there is noise, not navigation
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

    // Headers are never hover-highlighted
    @Override
    protected boolean isHovered(int mouseX, int mouseY) {
        return false;
    }

    // Label only, no background
    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        String icon = Objects.requireNonNullElse(drawContext.getModLogoPath(this.modId), FALLBACK_TEXTURE);
        int iconY = this.dim.getCenterY() - (ICON_SIZE / 2);
        drawContext.blitWholeImage(icon, this.dim.x() + ICON_PADDING, iconY, ICON_SIZE, ICON_SIZE);

        // Text starts past the icon and both of its paddings, so every heading's name lines up regardless of
        // whether the mod supplied a logo or fell back to the unknown-pack texture
        int textX = this.dim.x() + ICON_PADDING + ICON_SIZE + ICON_PADDING;
        var name = drawContext.getFriendlyModName(this.modId);
        int accentColor = drawContext.getModAccentColor(this.modId);

        // Vertically centred against the row rather than the icon, so the name stays put if ICON_SIZE changes
        drawContext.drawString(name, textX, this.dim.getCenterY() - (drawContext.lineHeight() / 2), accentColor);
    }
}
