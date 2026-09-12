package com.bdmajora.impetus.umbra.targets;

import java.util.BitSet;

// Tracks per colour buffer whether main and alt are swapped: a pass samples the front and renders into the back, then flips (OptiFine's flipBuffers); a BitSet since the flip state is snapshotted per pass at schedule time and cloning is one word
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

    // A copy, not the live set: each pass records the flip state it runs under at SCHEDULE time while the flipper keeps mutating
    public BitSet snapshot() {
        return (BitSet) this.flippedBuffers.clone();
    }

    // Back to all-unflipped at frame start, so pass N always runs under the flip state it was scheduled with
    public void reset() {
        this.flippedBuffers.clear();
    }
}
