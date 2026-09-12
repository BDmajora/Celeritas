package com.bdmajora.impetus.engine.impl.util.sorting;


public class AbstractSort {
    // 0..length-1, the identity permutation
    protected static int[] createIndexBuffer(int length) {
        var indices = new int[length];

        for (int i = 0; i < length; i++) {
            indices[i] = i;
        }

        return indices;
    }
}
