package com.bdmajora.impetus.umbra.uniforms;

import com.bdmajora.impetus.umbra.gl.program.ProgramUniforms;
import com.bdmajora.impetus.umbra.gl.uniform.UniformCollector;
import com.bdmajora.impetus.umbra.gl.uniform.UniformUpdateFrequency;
import org.joml.Vector2i;
import org.joml.Vector3i;

import java.time.LocalDateTime;

/**
 * The wall-clock frame uniforms: {@code frameTimeCounter} (seconds since load, wrapping at 3600 to preserve float
 * precision, exactly as OptiFine does) and {@code frameCounter} (an int incremented per frame, wrapping at 720720).
 * <p>
 * {@link #COUNTER} must be ticked once per frame via {@link Timer#beginFrame(long)} from the frame-setup hook.
 */
public final class SystemTimeUniforms {
    public static final Timer COUNTER = new Timer();

    private SystemTimeUniforms() {
    }

    public static void addSystemTimeUniforms(UniformCollector uniforms) {
        uniforms
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "frameTimeCounter", COUNTER::getFrameTimeCounter)
                .uniform1f(UniformUpdateFrequency.PER_FRAME, "frameTime", COUNTER::getLastFrameTime)
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "frameCounter", COUNTER::getFrameCounter)
                .uniform3i(UniformUpdateFrequency.PER_TICK, "currentDate", SystemTimeUniforms::getCurrentDate)
                .uniform3i(UniformUpdateFrequency.PER_TICK, "currentTime", SystemTimeUniforms::getCurrentTime)
                .uniform2i(UniformUpdateFrequency.PER_TICK, "currentYearTime", SystemTimeUniforms::getCurrentYearTime);
    }

    private static Vector3i getCurrentDate() {
        LocalDateTime now = LocalDateTime.now();
        return new Vector3i(now.getYear(), now.getMonthValue(), now.getDayOfMonth());
    }

    private static Vector3i getCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        return new Vector3i(now.getHour(), now.getMinute(), now.getSecond());
    }

    private static Vector2i getCurrentYearTime() {
        LocalDateTime now = LocalDateTime.now();
        int elapsed = ((now.getDayOfYear() - 1) * 86400)
                + (now.getHour() * 3600)
                + (now.getMinute() * 60)
                + now.getSecond();
        return new Vector2i(elapsed, now.toLocalDate().lengthOfYear() * 86400 - elapsed);
    }

    public static final class Timer {
        private static final int FRAME_COUNTER_WRAP = 720720;
        private static final float FRAME_TIME_WRAP_SECONDS = 3600.0f;

        private int frameCounter;
        private float frameTimeCounter;
        private float lastFrameTime;
        private long lastFrameTimeNanos = -1L;

        /** Advances the counters using the supplied monotonic timestamp (nanoseconds), e.g. {@code System.nanoTime()}. */
        public void beginFrame(long nowNanos) {
            if (this.lastFrameTimeNanos >= 0) {
                float deltaSeconds = (nowNanos - this.lastFrameTimeNanos) / 1_000_000_000.0f;
                this.lastFrameTime = deltaSeconds;
                this.frameTimeCounter += deltaSeconds;
                if (this.frameTimeCounter > FRAME_TIME_WRAP_SECONDS) {
                    this.frameTimeCounter -= FRAME_TIME_WRAP_SECONDS;
                }
            }
            this.lastFrameTimeNanos = nowNanos;
            this.frameCounter = (this.frameCounter + 1) % FRAME_COUNTER_WRAP;
        }

        /** The previous frame's duration in seconds (OptiFine's {@code frameTime}). */
        public float getLastFrameTime() {
            return this.lastFrameTime;
        }

        public float getFrameTimeCounter() {
            return this.frameTimeCounter;
        }

        public int getFrameCounter() {
            return this.frameCounter;
        }

        public void reset() {
            this.frameCounter = 0;
            this.frameTimeCounter = 0.0f;
            this.lastFrameTimeNanos = -1L;
        }
    }
}
