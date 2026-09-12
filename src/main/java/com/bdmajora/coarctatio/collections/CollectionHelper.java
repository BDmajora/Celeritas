package com.bdmajora.coarctatio.collections;

import java.util.List;

// Helpers for compacting collections that are finished being built
public final class CollectionHelper {
    // Static-only
    private CollectionHelper() {
    }

    // Compacts a never-modified list into a FixedArrayList; null and already-fixed lists pass through so in-place re-baking is harmless
    public static <T> List<T> fixed(List<T> src) {
        if (src == null || src instanceof FixedArrayList) {
            return src;
        }

        return new FixedArrayList<>(src);
    }
}
