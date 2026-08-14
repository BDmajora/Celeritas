package com.bdmajora.impetus.iris.devtool;

import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;
import com.bdmajora.impetus.iris.uniforms.CelestialUniforms;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * One-shot dump of the celestial uniforms as a composite/final pass sees them, plus the sun's projected screen
 * position computed exactly the way a pack does it.
 * <p>
 * Written for Body Camera's lens flare, whose {@code final.vsh} anchors all 13 ghosts on
 * {@code screenSunPosition = (gbufferProjection * vec4(normalize(sunPosition), 1.0)).xy / w * 0.5 + 0.5}. Measuring the
 * rendered frame showed the ghosts collapsing onto the screen centre — {@code screenSunPosition ≈ (0.5, 0.5)} — while
 * the sun itself renders at {@code v ≈ 0.865}. Arithmetic rules out the projection matrix being the culprit: a
 * transposed one puts {@code ndc.y} at 7.3 and a zeroed one yields NaN, and either way the pack's in-range test fails
 * and no flare is drawn at all. Only an eye-space sun direction with no x/y component fits what is on screen, so this
 * prints the direction and the matrices behind it rather than guessing further.
 * <p>
 * OFF unless {@code -Dimpetus.iris.sunProbeFrame=<n>} names a frame to sample; pick a frame after the world is up
 * (a few hundred), then look at the sun and read the single {@code SunProbe} line.
 */
public final class SunProbe {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");
    private static final int PROBE_FRAME = Integer.getInteger("impetus.iris.sunProbeFrame", -1);

    private static int frame;
    private static boolean done;

    private SunProbe() {
    }

    /** Call once per frame from the composite/final entry point, where a pack would read these uniforms. */
    public static void sample() {
        if (PROBE_FRAME < 0 || done || frame++ < PROBE_FRAME) {
            return;
        }
        done = true;

        try {
            Vector3f sun = CelestialUniforms.getSunPosition();
            Vector3f up = CelestialUniforms.getUpPosition();
            Matrix4f projection = CapturedRenderingState.INSTANCE.getGbufferProjection();
            Matrix4f modelView = CapturedRenderingState.INSTANCE.getGbufferModelView();

            float length = sun.length();
            // The pack's normalize(): if sunPosition is degenerate this is where the whole thing dies.
            Vector3f direction = length > 1.0e-6f ? new Vector3f(sun).div(length) : new Vector3f();
            Vector4f clip = new Vector4f(direction.x, direction.y, direction.z, 1.0f);
            projection.transform(clip);

            String projected;
            if (Math.abs(clip.w) < 1.0e-9f) {
                projected = "w=" + clip.w + " -> divide by zero, pack's range test fails, no flare";
            } else {
                float ndcX = clip.x / clip.w;
                float ndcY = clip.y / clip.w;
                float ndcZ = clip.z / clip.w;
                boolean inRange = ndcX >= -1.0f && ndcX <= 1.0f && ndcY >= -1.0f && ndcY <= 1.0f
                        && ndcZ >= -1.0f && ndcZ <= 1.0f;
                projected = String.format("ndc=(%.4f, %.4f, %.4f) inRange=%s screenSunPosition=(%.4f, %.4f)",
                        ndcX, ndcY, ndcZ, inRange, ndcX * 0.5f + 0.5f, ndcY * 0.5f + 0.5f);
            }

            LOGGER.info("[SunProbe] frame={} celestialAngle={} sunAngle={}", PROBE_FRAME,
                    CelestialUniforms.getCelestialAngle(), CelestialUniforms.getSunAngle());
            LOGGER.info("[SunProbe] sunPosition={} (length {}) normalized={}", sun, length, direction);
            LOGGER.info("[SunProbe] upPosition={} (expect ~(0,100,0) rotated into eye space)", up);
            LOGGER.info("[SunProbe] {}", projected);
            LOGGER.info("[SunProbe] gbufferModelView=\n{}", modelView);
            LOGGER.info("[SunProbe] gbufferProjection=\n{}", projection);
        } catch (RuntimeException e) {
            LOGGER.warn("[SunProbe] failed", e);
        }
    }
}
