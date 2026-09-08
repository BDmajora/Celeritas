package com.bdmajora.impetus.api.eventbus;

// Base class for all Impetus events; on (Neo)Forge this would extend their native event class to hook their bus, on Fabric it extends nothing
public abstract class ImpetusEvent {
    public boolean isCancelable() {
        return false;
    }

    private boolean canceled;

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean cancel) {
        canceled = cancel;
    }
}
