package com.bdmajora.coartatio.collections;

import java.util.List;

// Helpers for compacting collections that are finished being built
public final class CollectionHelper {
    // Static-only
    private CollectionHelper() {
    }

    // Compacts a list that will never be modified again into a FixedArrayList
    // Returns null and already-fixed lists untouched, so a mod re-baking a model in place is harmless
    public static <T> List<T> fixed(List<T> src) {
        if (src == null || src instanceof FixedArrayList) {
            return src;
        }

        return new FixedArrayList<>(src);
    }
}
