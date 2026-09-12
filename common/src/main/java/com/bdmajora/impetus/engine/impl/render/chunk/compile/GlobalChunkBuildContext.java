package com.bdmajora.impetus.engine.impl.render.chunk.compile;

import org.jetbrains.annotations.Nullable;

public final class GlobalChunkBuildContext {
    private static ChunkBuildContext mainThreadContext;
    private static Thread mainThread;

    private GlobalChunkBuildContext() {}

    // Records the caller as the main thread
    public static void setMainThread() {
        mainThread = Thread.currentThread();
    }

    // This thread's context, from the worker or the main thread binding
    @Nullable
    public static ChunkBuildContext get() {
        var thread = Thread.currentThread();
        // Main thread first, because it's the most common case
        if(thread == mainThread) {
            return mainThreadContext;
        } else if(thread instanceof Holder holder) {
            return holder.impetus$getGlobalContext();
        } else {
            return null;
        }
    }

    // Gives the main thread a context so it can steal jobs
    public static void bindMainThread(ChunkBuildContext context) {
        mainThreadContext = context;
    }

    public interface Holder {
        ChunkBuildContext impetus$getGlobalContext();
    }
}
