package com.bdmajora.impetus.engine.impl.compat.platform;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// Blocking Swing warning dialog for problems a log line would miss (e.g. a driver known to crash on launch); falls back to logging when headless
public final class MessageBoxUtil {
    private static final Logger LOGGER = LogManager.getLogger("Impetus");

    private MessageBoxUtil() {
    }

    // Native warning dialog
    public static void showWarning(String title, String message) {
        show(title, message, javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    // Native error dialog
    public static void showError(String title, String message) {
        show(title, message, javax.swing.JOptionPane.ERROR_MESSAGE);
    }

    // Through TinyFD when available, else Swing
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
            // AWT can fail in exotic launcher setups (e.g. macOS without -XstartOnFirstThread); the warning is already logged, so never let the dialog crash the game
        }
    }
}
