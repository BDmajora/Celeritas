package com.bdmajora.impetus.api.eventbus;

// Base class for all Impetus events; on (Neo)Forge this would extend their native event class to hook their bus, on Fabric it extends nothing
public abstract class ImpetusEvent {
    // Defaults to no
    public boolean isCancelable() {
        return false;
    }

    private boolean canceled;

    // Whether a handler cancelled it
    public boolean isCanceled() {
        return canceled;
    }

    // Throws on a non-cancelable event
    public void setCanceled(boolean cancel) {
        canceled = cancel;
    }
}
