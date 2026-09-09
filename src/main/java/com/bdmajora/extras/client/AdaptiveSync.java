package com.bdmajora.extras.client;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;

import java.lang.reflect.Method;

/**
 * Adaptive VSync — swap interval -1, which honours VSync above the refresh rate and disengages below
 * it, so a dropped frame costs one frame rather than half the refresh period.
 *
 * <p>Sodium Extra and Celeritas Extra both implement this by mixing into the window's swap-interval
 * call. That is not available here: on stock Forge 1.12.2, {@code org.lwjgl.} is in LaunchWrapper's
 * class-loader exclusions, so a mixin targeting {@link Display} is never given the chance to apply
 * and would fail the config's {@code required} check. Applying it from the outside instead works on
 * both windowing backends and needs no mixin at all.
 *
 * <p>Everything GLFW is reached reflectively: the LWJGL3 classes exist only on Cleanroom-style
 * launchers, and {@code src/main} compiles against LWJGL2. On LWJGL2 there is no swap-interval API
 * at all, so {@link #isSupported()} is false and the option is not offered.
 */
public final class AdaptiveSync {
    private static final String GLFW_CLASS = "org.lwjgl.glfw.GLFW";

    private static Boolean supported;
    private static Method swapInterval;

    private AdaptiveSync() {
    }

    /**
     * Whether the driver advertises tear control, i.e. whether a swap interval of -1 means anything.
     *
     * <p>Resolved once. A driver that gains the extension mid-session is not a case worth paying a
     * lookup per frame for.
     */
    public static boolean isSupported() {
        Boolean cached = supported;
        if (cached != null) {
            return cached;
        }

        boolean result = false;
        try {
            Class<?> glfw = Class.forName(GLFW_CLASS);
            Method extensionSupported = glfw.getMethod("glfwExtensionSupported", CharSequence.class);

            result = (Boolean) extensionSupported.invoke(null, "GLX_EXT_swap_control_tear")
                    || (Boolean) extensionSupported.invoke(null, "WGL_EXT_swap_control_tear");

            if (result) {
                swapInterval = glfw.getMethod("glfwSwapInterval", int.class);
            }
        } catch (ClassNotFoundException e) {
            // LWJGL2: no swap-interval control exists, so adaptive sync is simply not a mode here.
        } catch (Throwable t) {
            Extras.LOGGER.warn("Could not determine adaptive VSync support; assuming unsupported", t);
        }

        supported = result;
        return result;
    }

    /** The mode currently in effect, derived from the vanilla VSync flag and the adaptive switch. */
    public static ExtrasConfig.VerticalSync current() {
        ExtrasConfig options = Extras.options();

        if (options.extra.useAdaptiveSync && isSupported()) {
            return ExtrasConfig.VerticalSync.ADAPTIVE;
        }

        return Minecraft.getMinecraft().gameSettings.enableVsync
                ? ExtrasConfig.VerticalSync.ON
                : ExtrasConfig.VerticalSync.OFF;
    }

    /**
     * Applies a mode, updating the vanilla setting alongside it so the two never disagree.
     *
     * <p>ADAPTIVE turns vanilla VSync on first and then overrides the interval, because that is what
     * "adaptive" degrades to when the driver ignores -1. Choosing it without driver support falls
     * back to plain ON rather than silently doing nothing.
     */
    public static void apply(ExtrasConfig.VerticalSync mode) {
        ExtrasConfig options = Extras.options();
        Minecraft minecraft = Minecraft.getMinecraft();

        boolean adaptive = mode == ExtrasConfig.VerticalSync.ADAPTIVE && isSupported();
        boolean vsync = mode != ExtrasConfig.VerticalSync.OFF;

        options.extra.useAdaptiveSync = adaptive;
        minecraft.gameSettings.enableVsync = vsync;
        Display.setVSyncEnabled(vsync);

        if (adaptive) {
            setSwapInterval(-1);
        }

        minecraft.gameSettings.saveOptions();
    }

    /**
     * Re-asserts the adaptive interval.
     *
     * <p>Anything that calls {@code Display.setVSyncEnabled} — the vanilla video settings screen,
     * Impetus' own VSync tickbox — resets the interval to 0 or 1 behind our back.
     */
    public static void reapply() {
        if (Extras.options().extra.useAdaptiveSync && isSupported()) {
            setSwapInterval(-1);
        }
    }

    private static void setSwapInterval(int interval) {
        Method method = swapInterval;
        if (method == null) {
            return;
        }

        try {
            method.invoke(null, interval);
        } catch (Throwable t) {
            Extras.LOGGER.warn("Could not set the swap interval; disabling adaptive VSync", t);
            supported = false;
            swapInterval = null;
            Extras.options().extra.useAdaptiveSync = false;
        }
    }
}
