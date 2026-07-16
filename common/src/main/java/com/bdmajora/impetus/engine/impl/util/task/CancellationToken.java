package com.bdmajora.impetus.engine.impl.util.task;

public interface CancellationToken {
    boolean isCancelled();

    void setCancelled();
}
