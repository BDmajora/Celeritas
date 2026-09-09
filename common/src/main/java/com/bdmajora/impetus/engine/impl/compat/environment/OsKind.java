package com.bdmajora.impetus.engine.impl.compat.environment;

import java.util.Locale;

// Coarse operating-system classification for the platform-compatibility layer
// Detection is string-based on purpose: it has to behave identically on Java 8 after bytecode downgrading and on
// modern JVMs, across every launcher, and the newer OS-detection APIs are not available on all of those
public enum OsKind {
    WINDOWS,
    LINUX,
    MACOS,
    UNKNOWN;

    private static final OsKind CURRENT = detect();

    public static OsKind current() {
        return CURRENT;
    }

    private static OsKind detect() {
        var name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (name.contains("win")) {
            return WINDOWS;
        } else if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        } else if (name.contains("linux") || name.contains("bsd") || name.contains("unix")) {
            return LINUX;
        }

        return UNKNOWN;
    }
}
