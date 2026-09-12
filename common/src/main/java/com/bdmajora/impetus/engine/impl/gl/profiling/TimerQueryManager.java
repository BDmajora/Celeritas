package com.bdmajora.impetus.engine.impl.gl.profiling;

import com.bdmajora.impetus.lwjgl.GL32;
import com.bdmajora.impetus.lwjgl.GL33;
import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import lombok.Getter;

import java.io.Closeable;

public class TimerQueryManager implements Closeable {
    private static final int INVALID_ID = -1;
    // How many frames to wait before reading a timer query back
    // Reading one the same frame it was issued forces a full CPU/GPU sync, which is precisely the stall a profiler
    // must not introduce — three frames is comfortably past any driver's queue depth
    private static final int QUERY_FRAME_LAG_COUNT = 3;

    // A start and end timestamp query pair awaiting results
    private record InFlightQuery(int startTime, int endTime) {
        long getTimeDelta() {
            long startTime = LWJGL.glGetQueryObjectui64(this.startTime, GL32.GL_QUERY_RESULT);
            long endTime = LWJGL.glGetQueryObjectui64(this.endTime, GL32.GL_QUERY_RESULT);
            return endTime - startTime;
        }

        void delete() {
            releaseQuery(startTime);
            releaseQuery(endTime);
        }
    }

    private final ObjectArrayFIFOQueue<InFlightQuery> inFlightQueries = new ObjectArrayFIFOQueue<>();
    private int startQueryId = INVALID_ID;

    private static final IntArrayFIFOQueue QUERY_POOL = new IntArrayFIFOQueue();

    @Getter
    private long lastTime;

    // From the pool, or a fresh glGenQueries
    private static int allocateQuery() {
        if (!QUERY_POOL.isEmpty()) {
            return QUERY_POOL.dequeueInt();
        } else {
            return LWJGL.glGenQueries();
        }
    }

    // Back to the pool
    private static void releaseQuery(int id) {
        QUERY_POOL.enqueue(id);
    }

    // Issues the start timestamp
    public void startProfiling() {
        if (startQueryId != INVALID_ID) {
            throw new IllegalStateException("Query already started but not ended");
        }
        int id = allocateQuery();
        LWJGL.glQueryCounter(id, GL33.GL_TIMESTAMP);
        startQueryId = id;
    }

    // Issues the end timestamp and queues the pair
    public void finishProfiling() {
        if (startQueryId == INVALID_ID) {
            throw new IllegalStateException("Trying to end query that hasn't started yet");
        }
        int id = allocateQuery();
        LWJGL.glQueryCounter(id, GL33.GL_TIMESTAMP);
        inFlightQueries.enqueue(new InFlightQuery(startQueryId, id));
        startQueryId = -1;
    }

    // Reads back any completed pairs into the running total
    public void updateTime() {
        if (inFlightQueries.size() < QUERY_FRAME_LAG_COUNT) {
            return;
        }
        var query = inFlightQueries.dequeue();
        lastTime = query.getTimeDelta();
        query.delete();
    }

    // Deletes every query
    @Override
    public void close() {
        while (!inFlightQueries.isEmpty()) {
            inFlightQueries.dequeue().delete();
        }
        if (startQueryId != -1) {
            releaseQuery(startQueryId);
            startQueryId = -1;
        }
    }
}
