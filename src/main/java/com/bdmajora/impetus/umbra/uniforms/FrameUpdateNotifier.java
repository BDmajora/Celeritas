package com.bdmajora.impetus.umbra.uniforms;

import java.util.ArrayList;
import java.util.List;

// Per-frame callback fanout for the uniform helpers that must advance exactly once per frame (system-time counters, eye-brightness smoother); driving them from update() would step once per reader
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
