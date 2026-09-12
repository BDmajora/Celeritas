package com.bdmajora.impetus.umbra.uniforms;

import java.util.ArrayList;
import java.util.List;

// Per-frame callback fanout for the uniform helpers that have to advance exactly once per rendered frame
// Needed because those helpers (the system-time counters, the eye-brightness smoother) are read by many uniforms
// but must only STEP once — driving them from update() would advance them once per reader instead
public final class FrameUpdateNotifier {
    // Registered at pipeline build and never removed: the notifier dies with the pipeline it belongs to
    private final List<Runnable> listeners = new ArrayList<>();

    // Called once per frame before uniforms upload
    public void addListener(Runnable listener) {
        this.listeners.add(listener);
    }

    // Called once at the top of the frame, before any program is bound and any uniform is read
    public void onNewFrame() {
        for (Runnable listener : this.listeners) {
            listener.run();
        }
    }
}
