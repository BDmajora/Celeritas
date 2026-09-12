package com.bdmajora.extras.client;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;

import java.lang.reflect.Method;

// Adaptive VSync (swap interval -1 honours VSync above the refresh rate, disengages below); applied from outside via reflective GLFW since org.lwjgl. is class-loader excluded on Forge, and not offered on LWJGL2
public final class AdaptiveSync {
    private static final String GLFW_CLASS = "org.lwjgl.glfw.GLFW";

    private static Boolean supported;
    private static Method swapInterval;

    private AdaptiveSync() {
    }

    // Whether the driver advertises tear control, i.e. whether -1 means anything; resolved once since a driver gaining it mid-session is not worth a lookup per frame
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

    // The mode currently in effect, derived from the vanilla VSync flag and the adaptive switch.
    public static ExtrasConfig.VerticalSync current() {
        ExtrasConfig options = Extras.options();

        if (options.extra.useAdaptiveSync && isSupported()) {
            return ExtrasConfig.VerticalSync.ADAPTIVE;
        }

        return Minecraft.getMinecraft().gameSettings.enableVsync
                ? ExtrasConfig.VerticalSync.ON
                : ExtrasConfig.VerticalSync.OFF;
    }

    // Applies a mode and updates the vanilla setting so the two never disagree; ADAPTIVE turns vanilla VSync on then overrides the interval, and falls back to plain ON without driver support
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

    // Re-asserts the adaptive interval, since anything calling Display.setVSyncEnabled (vanilla video settings, Impetus' VSync tickbox) resets it behind our back
    public static void reapply() {
        if (Extras.options().extra.useAdaptiveSync && isSupported()) {
            setSwapInterval(-1);
        }
    }

    // Calls glfwSwapInterval through the resolved handle; a failure is logged and the option left as it was
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
