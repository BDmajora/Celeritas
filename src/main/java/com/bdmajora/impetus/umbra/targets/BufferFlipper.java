package com.bdmajora.impetus.umbra.targets;

import java.util.BitSet;

// Tracks, per colour buffer, whether its "main" and "alt" textures are currently swapped
// The composite chain ping-pongs: a pass writing colortexN samples the current front texture and renders into the
// back one, then flips buffer N so the next pass sees what was just written as ITS front. Same A/B swap OptiFine
// does in flipBuffers
// A BitSet rather than a boolean[] because the flip state is snapshotted per pass at schedule time, and cloning a
// BitSet is one word copy for all 16 buffers
public class BufferFlipper {
    private final BitSet flippedBuffers = new BitSet();

    // Swaps front and back for one buffer
    public void flip(int target) {
        this.flippedBuffers.flip(target);
    }

    // Whether the buffer's alt texture is currently front
    public boolean isFlipped(int target) {
        return this.flippedBuffers.get(target);
    }

    // A copy, not the live set: each pass records the flip state it will run under at SCHEDULE time, and the
    // flipper keeps mutating as later passes are scheduled
    public BitSet snapshot() {
        return (BitSet) this.flippedBuffers.clone();
    }

    // Back to all-unflipped at the start of each frame, so pass N always runs under the same flip state it was
    // scheduled with
    public void reset() {
        this.flippedBuffers.clear();
    }
}
