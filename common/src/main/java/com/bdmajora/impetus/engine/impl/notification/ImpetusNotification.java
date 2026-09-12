package com.bdmajora.impetus.engine.impl.notification;

import java.util.List;

public record ImpetusNotification(Level level, String title, List<String> lines, long createdAtMillis, long durationMillis) {
    public enum Level {
        INFO,
        WARNING,
        ERROR
    }

    // Past its creation time plus duration
    public boolean isExpired(long nowMillis) {
        return nowMillis - this.createdAtMillis > this.durationMillis;
    }
}
