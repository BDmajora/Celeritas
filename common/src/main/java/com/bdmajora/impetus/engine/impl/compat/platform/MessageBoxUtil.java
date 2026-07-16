package com.bdmajora.impetus.engine.impl.compat.platform;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shows a blocking warning dialog for problems severe enough that a log line would be missed (e.g. a driver
 * known to crash on launch). Uses Swing rather than native message boxes: it is available on every platform
 * and Java version Impetus targets, and this path only runs during startup, before the GL loop is
 * latency-sensitive. Falls back to logging in headless environments.
 */
public final class MessageBoxUtil {
    private static final Logger LOGGER = LogManager.getLogger("Impetus");

    private MessageBoxUtil() {
    }

    public static void showWarning(String title, String message) {
        LOGGER.warn("{}: {}", title, message.replace('\n', ' '));

        if (Boolean.getBoolean("impetus.hideMessageBoxes") || java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }

        try {
            javax.swing.JOptionPane.showMessageDialog(null, message, title, javax.swing.JOptionPane.WARNING_MESSAGE);
        } catch (Throwable t) {
            // AWT can fail in exotic launcher setups (e.g. macOS without -XstartOnFirstThread juggling); the
            // warning is already in the log, so never let the dialog itself take the game down.
            LOGGER.debug("Could not display message box", t);
        }
    }
}
