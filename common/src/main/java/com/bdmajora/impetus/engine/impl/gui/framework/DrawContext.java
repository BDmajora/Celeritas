package com.bdmajora.impetus.engine.impl.gui.framework;

import com.bdmajora.impetus.engine.impl.gui.theme.DefaultColors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface DrawContext extends FontMetricsProvider {
    void fill(int x1, int y1, int x2, int y2, int color);

    // Plain string without shadow
    default int drawString(String str, int x, int y, int color) {
        return drawString(TextComponent.literal(str), x, y, color);
    }

    // Component without shadow
    default int drawString(TextComponent str, int x, int y, int color) {
        return drawString(str, x, y, color, false);
    }

    int drawString(TextComponent str, int x, int y, int color, boolean shadow);

    void blitWholeImage(@NotNull String icon, int x, int y, int width, int height);

    void pushMatrix();

    void translate(double x, double y, double z);

    void popMatrix();

    void enableScissor(int x1, int y1, int x2, int y2);

    void disableScissor();

    // One-pixel outline from four fills
    default void drawBorder(int x1, int y1, int x2, int y2, int color) {
        fill(x1, y1, x2, y1 + 1, color);
        fill(x1, y2 - 1, x2, y2, color);
        fill(x1, y1, x1 + 1, y2, color);
        fill(x2 - 1, y1, x2, y2, color);
    }

    default @Nullable String getModLogoPath(String modId) {
        return null;
    }

    // Defaults to the id; platforms override with the display name
    default TextComponent getFriendlyModName(String modId) {
        return TextComponent.literal(modId);
    }

    // Defaults to the theme's per-mod colour
    default int getModAccentColor(String modId) {
        return DefaultColors.getModAccentColor(modId);
    }
}
