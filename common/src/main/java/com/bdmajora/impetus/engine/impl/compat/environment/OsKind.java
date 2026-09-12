package com.bdmajora.impetus.engine.impl.compat.environment;

import java.util.Locale;

// Coarse OS classification, string-based on purpose so it behaves identically on downgraded Java 8 and modern JVMs across every launcher
public enum OsKind {
    WINDOWS,
    LINUX,
    MACOS,
    UNKNOWN;

    private static final OsKind CURRENT = detect();

    // Detected once and cached
    public static OsKind current() {
        return CURRENT;
    }

    // From os.name
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
