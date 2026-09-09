package com.bdmajora.impetus.engine.impl.compat.platform;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// A blocking warning dialog, for problems severe enough that a log line would simply be missed — a driver known
// to crash on launch, for instance
// Swing rather than a native message box: Swing is present on every platform and Java version this targets, and
// this path only runs during startup, well before the GL loop is latency-sensitive
// Falls back to logging in a headless environment rather than throwing, since a CI or server run must not be
// stopped by a dialog nobody can dismiss
public final class MessageBoxUtil {
    private static final Logger LOGGER = LogManager.getLogger("Impetus");

    private MessageBoxUtil() {
    }

    public static void showWarning(String title, String message) {
        show(title, message, javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    public static void showError(String title, String message) {
        show(title, message, javax.swing.JOptionPane.ERROR_MESSAGE);
    }

    private static void show(String title, String message, int type) {
        if (type == javax.swing.JOptionPane.ERROR_MESSAGE) {
            LOGGER.error("{}: {}", title, message.replace('\n', ' '));
        } else {
            LOGGER.warn("{}: {}", title, message.replace('\n', ' '));
        }

        if (Boolean.getBoolean("impetus.hideMessageBoxes") || java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }

        try {
            javax.swing.JOptionPane.showMessageDialog(null, message, title, type);
        } catch (Throwable t) {
            // AWT can fail in exotic launcher setups (e.g. macOS without -XstartOnFirstThread juggling); the
            // warning is already in the log, so never let the dialog itself take the game down.
        }
    }
}
