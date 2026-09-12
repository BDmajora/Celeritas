package com.bdmajora.impetus.engine.impl.render.chunk.compile.tasks;

import com.bdmajora.impetus.engine.impl.render.chunk.compile.ChunkBuildContext;
import com.bdmajora.impetus.engine.impl.util.task.CancellationToken;

// Immutable, optionally prioritised job carrying everything needed to mesh or sort a chunk off-thread; copies its inputs on the main thread and returns a result to be handled back there
public abstract class ChunkBuilderTask<OUTPUT> {
    // Executes off the calling thread without touching global mutable state; polls cancellationToken and returns the data to upload on the main thread, or null if cancelled
    public abstract OUTPUT execute(ChunkBuildContext context, CancellationToken cancellationToken);
}
