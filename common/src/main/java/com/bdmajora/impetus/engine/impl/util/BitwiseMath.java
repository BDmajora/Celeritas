package com.bdmajora.impetus.engine.impl.util;

public class BitwiseMath {
    // Returns 1 if a < b else 0, valid for all values of a and b
    public static int lessThan(int a, int b) {
        return (a - b) >>> 31;
    }

    // Returns 1 if a > b else 0, valid for all values of a and b
    public static int greaterThan(int a, int b) {
        return (b - a) >>> 31;
    }
}