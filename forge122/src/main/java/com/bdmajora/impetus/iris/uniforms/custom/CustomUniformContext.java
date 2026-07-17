package com.bdmajora.impetus.iris.uniforms.custom;

/**
 * Resolution environment for evaluating a custom-uniform expression. Variable lookups resolve first against
 * pack-defined variables/uniforms (evaluated earlier this frame) and then against the built-in input uniforms.
 */
public interface CustomUniformContext {
    /** {@return the value of the named variable/uniform/builtin, or {@code null} if unknown} */
    CustomUniformValue resolve(String name);

    /**
     * {@return per-call persistent state slot for {@code smooth()}}. The parser assigns each {@code smooth(...)}
     * call a unique index so it can keep an exponentially-smoothed value across frames.
     */
    SmoothState smoothState(int index);

    /** {@return seconds elapsed since the previous frame, for time-based functions like {@code smooth}} */
    float frameTime();

    final class SmoothState {
        public boolean initialized;
        public float value;
    }
}
