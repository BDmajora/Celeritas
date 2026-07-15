package org.taumc.celeritas.iris.uniforms;

import java.util.ArrayList;
import java.util.List;

/**
 * Iris-style per-frame callback fanout for uniform helpers that need to advance exactly once per rendered frame.
 */
public final class FrameUpdateNotifier {
    private final List<Runnable> listeners = new ArrayList<>();

    public void addListener(Runnable listener) {
        this.listeners.add(listener);
    }

    public void onNewFrame() {
        for (Runnable listener : this.listeners) {
            listener.run();
        }
    }
}
