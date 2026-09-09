package com.bdmajora.impetus.engine.impl.model.light.smooth;

// bit flags marking which light properties have been computed for a given face
class AoCompletionFlags {
    // the light data has been retrieved from the cache
    public static final int HAS_LIGHT_DATA = 0b01;

    // the light data has been unpacked into normalised floating point values
    public static final int HAS_UNPACKED_LIGHT_DATA = 0b10;
}
