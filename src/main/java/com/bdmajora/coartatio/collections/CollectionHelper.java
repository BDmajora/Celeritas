package com.bdmajora.coartatio.collections;

import java.util.List;

public final class CollectionHelper {
    private CollectionHelper() {
    }

    // Compacts a list that will never be modified again into a FixedArrayList
    // Returns the argument untouched when it is null or already fixed, so calling it twice on the same object is
    // harmless — which matters because a mod re-baking a model in place will do exactly that
    public static <T> List<T> fixed(List<T> src) {
        if (src == null || src instanceof FixedArrayList) {
            return src;
        }

        return new FixedArrayList<>(src);
    }
}
