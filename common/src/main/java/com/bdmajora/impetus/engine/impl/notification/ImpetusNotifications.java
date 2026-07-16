package com.bdmajora.impetus.engine.impl.notification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ImpetusNotifications {
    private static final int MAX_VISIBLE = 4;
    private static final long DEFAULT_DURATION_MS = 12_000L;
    private static final List<ImpetusNotification> NOTIFICATIONS = new ArrayList<>();

    private ImpetusNotifications() {
    }

    public static void info(String title, String... lines) {
        push(ImpetusNotification.Level.INFO, title, DEFAULT_DURATION_MS, lines);
    }

    public static void warn(String title, String... lines) {
        push(ImpetusNotification.Level.WARNING, title, DEFAULT_DURATION_MS, lines);
    }

    public static void error(String title, String... lines) {
        push(ImpetusNotification.Level.ERROR, title, DEFAULT_DURATION_MS, lines);
    }

    public static synchronized void push(ImpetusNotification.Level level, String title, long durationMillis, String... lines) {
        long now = System.currentTimeMillis();
        pruneExpired(now);

        NOTIFICATIONS.removeIf(notification -> notification.title().equals(title) && notification.lines().equals(Arrays.asList(lines)));
        NOTIFICATIONS.add(new ImpetusNotification(level, title, List.of(lines), now, durationMillis));

        while (NOTIFICATIONS.size() > MAX_VISIBLE) {
            NOTIFICATIONS.remove(0);
        }
    }

    public static synchronized List<ImpetusNotification> getVisible() {
        pruneExpired(System.currentTimeMillis());
        return List.copyOf(NOTIFICATIONS);
    }

    private static void pruneExpired(long now) {
        NOTIFICATIONS.removeIf(notification -> notification.isExpired(now));
    }
}
