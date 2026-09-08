package com.bdmajora.impetus.engine.impl.render.chunk.map;

// Bitmask flags tracked per chunk position in ChunkTracker
public class ChunkStatus {
    public static final int FLAG_HAS_BLOCK_DATA = 1;
    public static final int FLAG_HAS_LIGHT_DATA = 2;
    public static final int FLAG_ALL = FLAG_HAS_BLOCK_DATA | FLAG_HAS_LIGHT_DATA;
}