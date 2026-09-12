package com.bdmajora.impetus.engine.impl.util.collections;

import java.util.Set;

public class SetUtil {
    // Immutable copy, on Java 8 which lacks Set.copyOf
    @SuppressWarnings("unchecked")
    public static <T> Set<T> copyOf(Set<T> set) {
        if (set.isEmpty()) {
            return Set.of();
        } else {
            return (Set<T>)Set.of(set.toArray());
        }
    }
}
