package com.bdmajora.impetus.engine.impl.util.sorting;


public class AbstractSort {
    protected static int[] createIndexBuffer(int length) {
        var indices = new int[length];

        for (int i = 0; i < length; i++) {
            indices[i] = i;
        }

        return indices;
    }
}
