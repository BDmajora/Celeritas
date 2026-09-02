package com.bdmajora.impetus.iris.devtool;

import com.bdmajora.impetus.iris.uniforms.CameraUniforms;
import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;
import com.bdmajora.impetus.iris.uniforms.CelestialUniforms;
import com.bdmajora.impetus.iris.uniforms.CommonUniforms;
import com.bdmajora.impetus.iris.uniforms.EyeBrightnessTracker;
import com.bdmajora.impetus.iris.uniforms.custom.ActiveCustomUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dumps every view- and lighting-dependent input the shader pack receives, so two captures taken from the same spot
 * facing different ways can be diffed directly.
 * <p>
 * The point of the {@code ROTATION-INVARIANT} block is that those quantities are the ones which <em>must not</em>
 * change when only the camera's yaw/pitch changes. {@code sunPosition}, {@code moonPosition}, {@code upPosition} and
 * {@code shadowLightPosition} are eye-space vectors, so their components are expected to differ between two headings —
 * but their lengths, and the angles between them, are camera-independent by construction, because every one of them is
 * the same world-space direction pushed through the same {@code gbufferModelView}. Packs light the scene from
 * {@code dot(normalize(sunPosition), normalize(upPosition))}; OptiFine guarantees that dot product is stable because
 * {@code setUpPosition} and {@code postCelestialRotate} both read the live modelview off the same camera transform
 * (Shaders.java:3921-3944). If any invariant here moves between two headings, the camera matrix is leaking into
 * something it should not, and that is the bug.
 * <p>
 * Deliberately cheap: no framebuffer readbacks, no texture downloads, no allocation beyond a few vectors and the
 * string builder. Unlike {@link ShadowMapDump} this is safe to leave enabled, so it runs on every screenshot without
 * a system property.
 */
public final class ShaderStateProbe {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    /**
     * Set by {@link #dump} and consumed by the next shadow pass, which is the only place the shadow textures are
     * readable. The result is logged from there rather than returned here, so each screenshot produces the probe
     * block immediately and a matching {@code SHADOW COVERAGE} line one frame later.
     */
    private static volatile boolean shadowCoverageRequested;

    /**
     * Hard cap on how many draws one screenshot inspects. This bounds the GL queries (each one is a pipeline stall),
     * not the log: what actually reaches the log is deduplicated by {@link #isNewDrawSignature}. Sized to cover every
     * item and block entity a dense scene draws in a frame rather than an arbitrary prefix of them — a previous
     * capture spent its entire 48-slot budget on 16 copies of one carpet and 8 of one stick and never reached
     * anything else.
     */
    private static final int DRAW_PROBE_SAMPLES = 2000;

    /** Set by {@link #dump} and spent by the draws that follow it. */
    private static final AtomicInteger drawProbeBudget = new AtomicInteger();

    /**
     * One entry per distinct draw state seen since the last {@link #dump}. Repeats of an identical state carry no
     * information — the question is which distinct states exist, so the log gets one line each.
     */
    private static final Set<String> seenDrawSignatures = ConcurrentHashMap.newKeySet();

    private static final AtomicInteger inspectedDraws = new AtomicInteger();
    private static final AtomicInteger skippedShadowDraws = new AtomicInteger();

    /** Whether a capture is open, so {@link #finishCapture} reports exactly once per screenshot. */
    private static volatile boolean captureActive;

    private ShaderStateProbe() {
    }

    /** {@return whether a shadow-coverage sample is pending, clearing the request} */
    public static boolean consumeShadowCoverageRequest() {
        boolean pending = shadowCoverageRequested;
        shadowCoverageRequested = false;
        return pending;
    }

    /** {@return whether this draw should be inspected, claiming one slot of the budget} */
    public static boolean consumeDrawProbeSlot() {
        int remaining;
        do {
            remaining = drawProbeBudget.get();
            if (remaining <= 0) {
                return false;
            }
        } while (!drawProbeBudget.compareAndSet(remaining, remaining - 1));
        inspectedDraws.incrementAndGet();
        return true;
    }

    /**
     * Counts a draw that was skipped because the shadow pass was running. Recorded rather than ignored so a capture
     * that logs nothing can be told apart from a capture that never ran — the distinction that cost several rounds
     * of debugging when every sample silently came from the shadow pass.
     */
    public static void noteShadowDrawSkipped() {
        if (captureActive) {
            skippedShadowDraws.incrementAndGet();
        }
    }

    /** {@return whether this exact draw state has not been logged yet in this capture} */
    public static boolean isNewDrawSignature(String signature) {
        return seenDrawSignatures.add(signature);
    }

    /**
     * Closes the capture and reports what it saw. Called at the end of the camera pass, so "0 camera-pass draws"
     * appears in the log as a positive statement instead of as silence.
     */
    public static void finishCapture() {
        if (!captureActive) {
            return;
        }
        captureActive = false;
        LOGGER.info("[Iris] DRAW probe summary: {} camera-pass draw(s) inspected, {} distinct state(s) logged, "
                        + "{} shadow-pass draw(s) skipped, {} of {} budget left. "
                        + "Zero inspected means nothing reached RenderItem/TileEntityRendererDispatcher in the "
                        + "camera pass this frame, which is a finding rather than a failed capture.",
                inspectedDraws.get(), seenDrawSignatures.size(), skippedShadowDraws.get(),
                drawProbeBudget.get(), DRAW_PROBE_SAMPLES);
    }

    /** Logs a full snapshot. {@code reason} is echoed so multiple captures can be told apart in the log. */
    public static void dump(String reason) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            Entity camera = mc.getRenderViewEntity();
            World world = mc.world;
            if (camera == null || world == null) {
                return;
            }

            StringBuilder out = new StringBuilder(2048);
            out.append("\n==== Impetus shader state probe (").append(reason).append(") ====");

            appendCamera(out, camera);
            appendEyeLight(out, camera, world);
            appendCelestial(out);
            appendInvariants(out);
            appendPackUniforms(out);

            out.append("\n  (SHADOW COVERAGE for this frame is logged separately by the next shadow pass)");
            out.append("\n==== end probe ====");
            LOGGER.info(out.toString());
            shadowCoverageRequested = true;
            // Reset every counter together: a capture must never inherit state from the previous screenshot, or a
            // second capture silently reports "0 distinct" because the first already claimed those signatures.
            seenDrawSignatures.clear();
            inspectedDraws.set(0);
            skippedShadowDraws.set(0);
            drawProbeBudget.set(DRAW_PROBE_SAMPLES);
            captureActive = true;
        } catch (Throwable t) {
            // A diagnostic must never be able to break the frame it is diagnosing.
            LOGGER.warn("[Iris] Shader state probe failed", t);
        }
    }

    private static void appendCamera(StringBuilder out, Entity camera) {
        Vector3d position = CameraUniforms.getCurrentCameraPositionUnshifted();
        float yaw = camera.rotationYaw;
        float pitch = camera.rotationPitch;

        // The heading the player is actually looking along, in world space. Pair this with the block coordinates so a
        // "glowing" capture and a "normal" capture can be matched up to the same spot.
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double lookX = -Math.sin(yawRadians) * Math.cos(pitchRadians);
        double lookY = -Math.sin(pitchRadians);
        double lookZ = Math.cos(yawRadians) * Math.cos(pitchRadians);

        out.append("\n  camera pos      : ").append(fmt(position.x)).append(", ")
                .append(fmt(position.y)).append(", ").append(fmt(position.z));
        out.append("\n  camera block    : ").append(Math.floor(position.x)).append(", ")
                .append(Math.floor(position.y)).append(", ").append(Math.floor(position.z));
        out.append("\n  yaw / pitch     : ").append(fmt(yaw)).append(" / ").append(fmt(pitch));
        out.append("\n  look vector     : ").append(fmt(lookX)).append(", ")
                .append(fmt(lookY)).append(", ").append(fmt(lookZ));
    }

    private static void appendEyeLight(StringBuilder out, Entity camera, World world) {
        // getBrightnessForRender() is what OptiFine feeds the eyeBrightness uniform (Shaders.java:3531), and what
        // EyeBrightnessTracker uses. getCombinedLight() at the eye block is shown alongside it because the two
        // disagreeing is a known failure mode for this uniform.
        int forRender = camera.getBrightnessForRender();
        BlockPos eyeBlock = new BlockPos(camera.posX, camera.posY + camera.getEyeHeight(), camera.posZ);
        int combined = world.getCombinedLight(eyeBlock, 0);

        Vector2i raw = EyeBrightnessTracker.getEyeBrightness();
        Vector2i smooth = EyeBrightnessTracker.getEyeBrightnessSmooth();

        out.append("\n  eye block       : ").append(eyeBlock.getX()).append(", ")
                .append(eyeBlock.getY()).append(", ").append(eyeBlock.getZ());
        out.append("\n  getBrightnessForRender : block ").append(forRender & 0xFFFF)
                .append(" sky ").append(forRender >> 16);
        out.append("\n  getCombinedLight       : block ").append(combined & 0xFFFF)
                .append(" sky ").append(combined >> 16);
        out.append("\n  eyeBrightness   : block ").append(raw.x).append(" sky ").append(raw.y).append(" (of 240)");
        out.append("\n  eyeBrightnessSmooth : block ").append(smooth.x).append(" sky ").append(smooth.y);
        // The built-in Java value. The pack normally overrides this name with its own expression (see the pack
        // uniform block below); printing both is how a divergence between the two becomes visible.
        out.append("\n  eyeBrightnessM (built-in) : ").append(fmt(CommonUniforms.getEyeBrightnessM()));
        out.append("\n  wetness         : ").append(fmt(EyeBrightnessTracker.getWetness()));
    }

    private static void appendCelestial(StringBuilder out) {
        out.append("\n  celestialAngle  : ").append(fmt(CelestialUniforms.getCelestialAngle()));
        out.append("\n  sunAngle        : ").append(fmt(CelestialUniforms.getSunAngle()));
        out.append("\n  shadowAngle     : ").append(fmt(CelestialUniforms.getShadowAngle()));
        out.append("\n  sunPathRotation : ").append(fmt(CelestialUniforms.getSunPathRotation()));

        Vector3f sun = CelestialUniforms.getSunPosition();
        Vector3f moon = CelestialUniforms.getMoonPosition();
        Vector3f up = CelestialUniforms.getUpPosition();
        Vector3f shadowWorld = CelestialUniforms.getShadowLightPositionInWorldSpace();

        out.append("\n  sunPosition     : ").append(vec(sun));
        out.append("\n  moonPosition    : ").append(vec(moon));
        out.append("\n  upPosition      : ").append(vec(up));
        out.append("\n  shadowLight(world) : ").append(vec(shadowWorld));
    }

    private static void appendInvariants(StringBuilder out) {
        Vector3f sun = CelestialUniforms.getSunPosition();
        Vector3f up = CelestialUniforms.getUpPosition();
        Vector3f moon = CelestialUniforms.getMoonPosition();

        out.append("\n  --- ROTATION-INVARIANT (must be identical between two headings at one spot) ---");
        out.append("\n  |sunPosition|   : ").append(fmt(sun.length()));
        out.append("\n  |upPosition|    : ").append(fmt(up.length()));
        out.append("\n  dot(sun^, up^)  : ").append(fmt(dotNormalized(sun, up)));
        out.append("\n  dot(moon^, up^) : ").append(fmt(dotNormalized(moon, up)));

        // The camera matrix itself. If the dot products above drift, this is what drifted underneath them; if they
        // hold but the scene still changes, the divergence is downstream of the matrices.
        Matrix4f modelView = CapturedRenderingState.INSTANCE.getGbufferModelView();
        out.append("\n  gbufferModelView determinant : ").append(fmt(modelView.determinant()));
        out.append("\n  gbufferModelView translation : ")
                .append(fmt(modelView.m30())).append(", ")
                .append(fmt(modelView.m31())).append(", ")
                .append(fmt(modelView.m32()));
    }

    private static void appendPackUniforms(StringBuilder out) {
        Map<String, String> pack = ActiveCustomUniforms.snapshot();
        if (pack.isEmpty()) {
            out.append("\n  pack uniforms   : (none declared)");
            return;
        }
        out.append("\n  --- pack-declared uniforms/variables (these override built-ins of the same name) ---");
        for (Map.Entry<String, String> entry : pack.entrySet()) {
            out.append("\n  ").append(pad(entry.getKey())).append(" = ").append(entry.getValue());
        }
    }

    private static float dotNormalized(Vector3f a, Vector3f b) {
        float lengthA = a.length();
        float lengthB = b.length();
        if (lengthA == 0.0f || lengthB == 0.0f) {
            return Float.NaN;
        }
        return (a.x * b.x + a.y * b.y + a.z * b.z) / (lengthA * lengthB);
    }

    private static String vec(Vector3f v) {
        return fmt(v.x) + ", " + fmt(v.y) + ", " + fmt(v.z);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static String pad(String name) {
        StringBuilder padded = new StringBuilder(name);
        while (padded.length() < 34) {
            padded.append(' ');
        }
        return padded.toString();
    }
}
