package com.bdmajora.coartatio.collections;

import java.util.List;

public final class CollectionHelper {
    private CollectionHelper() {
    }

    /**
     * Compacts a list that will never be modified again.
     *
     * <p>Returns the argument unchanged when it is already fixed or empty, so this is safe to call
     * twice on the same object — which happens whenever a mod re-bakes a model in place.
     */
    public static <T> List<T> fixed(List<T> src) {
        if (src == null || src instanceof FixedArrayList) {
            return src;
        }

        return new FixedArrayList<>(src);
    }
}
