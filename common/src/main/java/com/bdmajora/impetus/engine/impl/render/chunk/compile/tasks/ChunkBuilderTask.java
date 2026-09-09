package com.bdmajora.impetus.engine.impl.render.chunk.compile.tasks;

import com.bdmajora.impetus.engine.impl.render.chunk.compile.ChunkBuildContext;
import com.bdmajora.impetus.engine.impl.util.task.CancellationToken;

// build tasks are immutable jobs, with optional prioritisation, carrying all the state needed to
// perform chunk mesh updates or quad sorting off the main thread
// when a task is constructed on the main thread it copies everything it needs to complete without
// further synchronisation, and is then scheduled for async execution on a thread pool
// once it completes it returns a build result holding any computed data that has to be handled back
// on the main thread
public abstract class ChunkBuilderTask<OUTPUT> {
    // executes the build task asynchronously from the calling thread; implementations must be careful
    // not to access or modify global mutable state
    // context is the build context to use, cancellationToken is queried to find out whether the task
    // has been cancelled
    // returns the build result - whatever data needs uploading on the main thread - or null if cancelled
    public abstract OUTPUT execute(ChunkBuildContext context, CancellationToken cancellationToken);
}
