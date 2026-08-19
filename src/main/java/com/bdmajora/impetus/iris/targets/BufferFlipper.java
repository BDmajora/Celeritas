package com.bdmajora.impetus.iris.targets;

import java.util.BitSet;

/**
 * Tracks, per color buffer, whether the "main" and "alt" textures are currently swapped.
 * <p>
 * The composite chain ping-pongs: a pass that writes {@code colortexN} reads the current front texture and writes the
 * back texture, then flips buffer {@code N} so the next pass sees the freshly written data as its front. This mirrors
 * OptiFine's {@code flipBuffers}/{@code A}-{@code B} swap logic.
 */
public class BufferFlipper {
    private final BitSet flippedBuffers = new BitSet();

    public void flip(int target) {
        this.flippedBuffers.flip(target);
    }

    public boolean isFlipped(int target) {
        return this.flippedBuffers.get(target);
    }

    public BitSet snapshot() {
        return (BitSet) this.flippedBuffers.clone();
    }

    public void reset() {
        this.flippedBuffers.clear();
    }
}
