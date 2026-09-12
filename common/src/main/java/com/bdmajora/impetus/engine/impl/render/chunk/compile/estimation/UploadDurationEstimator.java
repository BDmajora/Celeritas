package com.bdmajora.impetus.engine.impl.render.chunk.compile.estimation;

public final class UploadDurationEstimator {
    private static final double NEW_SAMPLE_WEIGHT = 0.05D;
    private static final double INITIAL_NANOS_PER_BYTE = 0.25D;

    private double nanosPerByte = INITIAL_NANOS_PER_BYTE;
    private boolean hasRealSamples;

    private long lastUploadBytes;
    private long lastUploadDurationNanos;
    private long lastUploadEstimateNanos;

    // Bytes to nanoseconds from the running throughput
    public long estimateUploadDuration(long byteCount) {
        if (byteCount <= 0) {
            return 0L;
        }

        return Math.max(1L, (long)(this.nanosPerByte * byteCount));
    }

    // Feeds an observation into the moving average
    public void recordUpload(long byteCount, long durationNanos) {
        if (byteCount <= 0 || durationNanos <= 0) {
            return;
        }

        this.lastUploadBytes = byteCount;
        this.lastUploadDurationNanos = durationNanos;
        this.lastUploadEstimateNanos = this.estimateUploadDuration(byteCount);

        double sampleNanosPerByte = (double)durationNanos / byteCount;

        if (this.hasRealSamples) {
            this.nanosPerByte = (this.nanosPerByte * (1.0D - NEW_SAMPLE_WEIGHT)) + (sampleNanosPerByte * NEW_SAMPLE_WEIGHT);
        } else {
            this.nanosPerByte = sampleNanosPerByte;
            this.hasRealSamples = true;
        }
    }

    // For the debug screen
    public long getLastUploadBytes() {
        return this.lastUploadBytes;
    }

    // For the debug screen
    public long getLastUploadDurationNanos() {
        return this.lastUploadDurationNanos;
    }

    // For the debug screen
    public long getLastUploadEstimateNanos() {
        return this.lastUploadEstimateNanos;
    }
}
