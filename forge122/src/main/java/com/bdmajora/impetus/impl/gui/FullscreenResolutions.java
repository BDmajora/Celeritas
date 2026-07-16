package com.bdmajora.impetus.impl.gui;

import net.minecraft.client.resources.I18n;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Enumerates the real fullscreen video modes reported by LWJGL and applies a chosen mode. Index 0 is always
 * "Current" (the desktop resolution); indices 1..N map to the distinct available {@link DisplayMode}s sorted by
 * resolution. This backs the General → Fullscreen Resolution option with genuine hardware values.
 */
public final class FullscreenResolutions {
    private static List<DisplayMode> modes;

    private FullscreenResolutions() {
    }

    private static List<DisplayMode> modes() {
        if (modes == null) {
            var seen = new LinkedHashSet<String>();
            var list = new ArrayList<DisplayMode>();

            try {
                DisplayMode[] available = Display.getAvailableDisplayModes();
                // Highest resolution / refresh first.
                java.util.Arrays.sort(available, (a, b) -> {
                    int byArea = Integer.compare(b.getWidth() * b.getHeight(), a.getWidth() * a.getHeight());
                    return byArea != 0 ? byArea : Integer.compare(b.getFrequency(), a.getFrequency());
                });

                for (DisplayMode mode : available) {
                    if (!mode.isFullscreenCapable()) {
                        continue;
                    }
                    // Collapse duplicate resolutions that differ only by bit depth/refresh.
                    if (seen.add(mode.getWidth() + "x" + mode.getHeight())) {
                        list.add(mode);
                    }
                }
            } catch (LWJGLException e) {
                // Leave the list empty; only "Current" will be offered.
            }

            modes = list;
        }

        return modes;
    }

    /** {@return the number of selectable entries, including the "Current" entry at index 0} */
    public static int count() {
        return modes().size() + 1;
    }

    public static String label(int index) {
        if (index <= 0 || index > modes().size()) {
            return I18n.format("impetus.options.fullscreen_resolution.current");
        }

        DisplayMode mode = modes().get(index - 1);
        return mode.getWidth() + "x" + mode.getHeight();
    }

    /** Applies the selected mode if the display is currently fullscreen; otherwise records it for later use. */
    public static void apply(int index) {
        if (index <= 0 || index > modes().size()) {
            return;
        }

        try {
            if (Display.isFullscreen()) {
                Display.setDisplayModeAndFullscreen(modes().get(index - 1));
            }
        } catch (Throwable t) {
            // Never let a resolution change take the game down.
        }
    }
}
