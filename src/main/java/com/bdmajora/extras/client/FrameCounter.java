package com.bdmajora.extras.client;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Arrays;

// Frame timings over a rolling five-second window as average / 1% low / 0.1% low (Sodium Extra's benchmarking definition, mean of the slowest N%); recomputed at most twice a second from a ring-buffer append per frame
@Mod.EventBusSubscriber(Side.CLIENT)
@SideOnly(Side.CLIENT)
public final class FrameCounter {
    private static final long WINDOW_NS = 5_000_000_000L;
    private static final long CACHE_INTERVAL_NS = 500_000_000L;

    private static long[] sampleTimes = new long[512];
    private static long[] sampleDeltas = new long[512];
    private static int sampleHead;
    private static int sampleCount;

    private static long lastFrameTime;
    private static long lastCacheTime;

    private static int cachedAverageFps;
    private static int cachedOnePercentLowFps;
    private static int cachedPointOnePercentLowFps;

    private FrameCounter() {
    }

    // Records one frame time per render tick and recomputes the percentiles on a fixed interval
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        long now = System.nanoTime();

        // The first observed frame only seeds the timer; there is nothing to measure against yet.
        if (lastFrameTime != 0) {
            long delta = now - lastFrameTime;
            if (delta > 0) {
                addSample(now, delta);
            }
        }
        lastFrameTime = now;

        while (sampleCount > 0 && now - sampleTimes[sampleHead] > WINDOW_NS) {
            sampleHead = (sampleHead + 1) % sampleTimes.length;
            sampleCount--;
        }

        if (now - lastCacheTime >= CACHE_INTERVAL_NS) {
            lastCacheTime = now;
            recalculate();
        }
    }

    // Mean over the sample window, cached between recalculations
    public static int getAverageFps() {
        return cachedAverageFps;
    }

    // FPS at the 1st percentile of frame times, cached
    public static int getOnePercentLowFps() {
        return cachedOnePercentLowFps;
    }

    // FPS at the 0.1st percentile of frame times, cached
    public static int getPointOnePercentLowFps() {
        return cachedPointOnePercentLowFps;
    }

    // Sorts the current window and derives the three figures; zeros when empty
    private static void recalculate() {
        int size = sampleCount;
        if (size == 0) {
            cachedAverageFps = 0;
            cachedOnePercentLowFps = 0;
            cachedPointOnePercentLowFps = 0;
            return;
        }

        long[] deltas = new long[size];
        long total = 0;
        for (int i = 0; i < size; i++) {
            long delta = sampleDeltas[(sampleHead + i) % sampleDeltas.length];
            deltas[i] = delta;
            total += delta;
        }

        Arrays.sort(deltas);

        double averageDelta = (double) total / size;
        cachedAverageFps = averageDelta > 0 ? (int) (1_000_000_000.0 / averageDelta) : 0;
        cachedOnePercentLowFps = percentileLow(deltas, 1.0);
        cachedPointOnePercentLowFps = percentileLow(deltas, 0.1);
    }

    // Appends to the ring, doubling the arrays when full
    private static void addSample(long time, long delta) {
        if (sampleCount == sampleTimes.length) {
            int capacity = sampleTimes.length * 2;
            long[] times = new long[capacity];
            long[] newDeltas = new long[capacity];

            for (int i = 0; i < sampleCount; i++) {
                int source = (sampleHead + i) % sampleTimes.length;
                times[i] = sampleTimes[source];
                newDeltas[i] = sampleDeltas[source];
            }

            sampleTimes = times;
            sampleDeltas = newDeltas;
            sampleHead = 0;
        }

        int tail = (sampleHead + sampleCount) % sampleTimes.length;
        sampleTimes[tail] = time;
        sampleDeltas[tail] = delta;
        sampleCount++;
    }

    // The mean of the slowest percent of frames, as an FPS figure.
    private static int percentileLow(long[] ascendingDeltas, double percent) {
        int count = (int) Math.ceil(ascendingDeltas.length * (percent / 100.0));
        if (count == 0) {
            count = 1;
        }

        long sum = 0;
        for (int i = ascendingDeltas.length - count; i < ascendingDeltas.length; i++) {
            sum += ascendingDeltas[i];
        }

        double averageDelta = (double) sum / count;
        return averageDelta > 0 ? (int) (1_000_000_000.0 / averageDelta) : 0;
    }
}
