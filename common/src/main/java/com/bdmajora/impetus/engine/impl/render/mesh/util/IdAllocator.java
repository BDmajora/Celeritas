package com.bdmajora.impetus.engine.impl.render.mesh.util;

import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;

// Hands out the densest ids it can and shrinks its high-water mark when the tail is released, since the pipeline scans 0..maxIndex() every frame for live regions
public class IdAllocator {
    private final IntSortedSet released = new IntAVLTreeSet();
    private int next;

    // Reuses a released id before growing
    public int allocate() {
        if (this.released.isEmpty()) {
            return this.next++;
        }

        int id = this.released.firstInt();
        this.released.remove(id);
        return id;
    }

    // Returns an id for reuse
    public void release(int id) {
        this.released.add(id);

        // Walk the high-water mark back over released ids at the tail, so freeing the last region actually shortens the per-frame scan
        while (!this.released.isEmpty() && this.released.lastInt() + 1 == this.next) {
            this.released.remove(--this.next);
        }
    }

    // One past the largest id ever handed out and not since reclaimed from the tail
    public int maxIndex() {
        return this.next;
    }
}
