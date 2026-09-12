package com.bdmajora.impetus.engine.impl.common.util;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class TimeUtil {
    private static final TimeUnit[] UNITS = TimeUnit.values();

    // Largest unit that keeps the value at least one
    private static TimeUnit chooseUnit(long duration, TimeUnit unit) {
        for (int i = UNITS.length - 1; i > unit.ordinal(); i--) {
            if (UNITS[i].convert(duration, unit) > 0) {
                return UNITS[i];
            }
        }
        return unit;
    }

    // ns, us, ms, s and so on
    private static String abbreviateTime(TimeUnit unit) {
        return switch (unit) {
            case NANOSECONDS -> "ns";
            case MICROSECONDS -> "us";
            case MILLISECONDS -> "ms";
            case SECONDS -> "s";
            case MINUTES -> "min";
            case HOURS -> "h";
            case DAYS -> "d";
        };
    }

    // Human-readable with an auto-chosen unit
    public static String stringifyTime(long duration, TimeUnit unit) {
        return stringifyTime(duration, unit, chooseUnit(duration, unit));
    }

    // Human-readable in a fixed unit
    public static String stringifyTime(long duration, TimeUnit unit, TimeUnit displayUnit) {
        double displayDuration = ((double)duration) / unit.convert(1, displayUnit);
        return String.format(Locale.ROOT, "%.4g %s", displayDuration, abbreviateTime(displayUnit));
    }
}
