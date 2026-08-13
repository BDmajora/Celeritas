package com.bdmajora.impetus.iris.uniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.joml.Vector2f;
import org.joml.Vector2i;

/**
 * The temporally smoothed per-frame uniforms: {@code eyeBrightness}/{@code eyeBrightnessSmooth} (lightmap coordinates
 * — block, sky, each 0..240 — at the camera's eye, used for the cave-entrance exposure fade) and {@code wetness}
 * (rain strength smoothed with OptiFine's wetness/dryness half-lives). Smoothing must advance exactly once per frame
 * ({@code update()} from the frame hook), not from the uniform suppliers (which run once per program).
 */
public final class EyeBrightnessTracker {
    /**
     * Iris's defaults, in <em>deciseconds</em> — the unit its {@code SmoothedFloat} takes, which scales the half-life
     * by {@code 0.1f} to get seconds ({@code SmoothedFloat.java:53}). So the shipped values mean 60s to get fully wet,
     * 20s to dry off, and 1s of eye-brightness smoothing.
     * <p>
     * These were previously hardcoded and, worse, interpreted as <em>ticks</em> ({@code halfLife / 20}), which halved
     * every one of them: 30s/10s/0.5s. Packs are tuned against the Iris/OptiFine rates.
     */
    private static final float DEFAULT_WETNESS_HALF_LIFE = 600.0f;
    private static final float DEFAULT_DRYNESS_HALF_LIFE = 200.0f;
    private static final float DEFAULT_EYE_BRIGHTNESS_HALF_LIFE = 10.0f;

    /** Deciseconds. Overwritten per pack load from the {@code const float *Halflife} directives. */
    private static volatile float wetnessHalfLife = DEFAULT_WETNESS_HALF_LIFE;
    private static volatile float drynessHalfLife = DEFAULT_DRYNESS_HALF_LIFE;
    private static volatile float eyeBrightnessHalfLife = DEFAULT_EYE_BRIGHTNESS_HALF_LIFE;

    private static final Vector2i eyeBrightness = new Vector2i();
    private static final Vector2f smoothed = new Vector2f();
    private static float wetness;
    private static long lastUpdateNanos = -1L;

    private EyeBrightnessTracker() {
    }

    /**
     * Installs the pack's {@code wetnessHalflife} / {@code drynessHalflife} / {@code eyeBrightnessHalflife}, in
     * deciseconds. Called once per pack load; a non-positive value means "snap instantly", which is what a half-life
     * of zero degenerates to.
     */
    public static void setHalfLives(float wetnessDeciseconds, float drynessDeciseconds,
                                   float eyeBrightnessDeciseconds) {
        wetnessHalfLife = wetnessDeciseconds;
        drynessHalfLife = drynessDeciseconds;
        eyeBrightnessHalfLife = eyeBrightnessDeciseconds;
    }

    /**
     * The exponential-smoothing blend factor for one frame: the fraction of the way to move toward the target so that
     * half the remaining distance is covered every {@code halfLifeDeciseconds}. Iris expresses the same thing as
     * {@code 1 - e^(-kt)} with {@code k = ln2 / (halfLife * 0.1)}.
     */
    private static float smoothingFactor(float halfLifeDeciseconds, float deltaSeconds) {
        if (halfLifeDeciseconds <= 0.0f) {
            return 1.0f;
        }
        return 1.0f - (float) Math.pow(0.5, deltaSeconds / (halfLifeDeciseconds * 0.1f));
    }

    /** Advances the tracker one frame. Called from the pipeline's frame-begin hook on the render thread. */
    public static void update() {
        long now = System.nanoTime();
        float deltaSeconds = lastUpdateNanos < 0 ? 1.0f : (now - lastUpdateNanos) / 1_000_000_000.0f;
        lastUpdateNanos = now;

        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.getRenderViewEntity();
        World world = mc.world;
        if (camera == null || world == null) {
            return;
        }
        // OptiFine reads this straight off the entity (Shaders.java: `eyeBrightness = entity.getBrightnessForRender()`),
        // and so do we. The hand-rolled `world.getCombinedLight(new BlockPos(posX, posY + eyeHeight, posZ), 0)` this
        // replaces looked equivalent but reported (0,0) — total darkness — for a camera standing in open daylight,
        // which makes Complementary believe the player is sealed underground: eyeBrightnessM collapses to 0 and the
        // scene-aware light shafts latch into their extreme cave mode. getBrightnessForRender() applies the
        // isBlockLoaded guard and the Y clamp that the direct call skips, and is what every 1.12 pack is tuned against.
        int combined = camera.getBrightnessForRender();
        eyeBrightness.set(combined & 0xFFFF, combined >> 16);

        float factor = smoothingFactor(eyeBrightnessHalfLife, deltaSeconds);
        smoothed.x += (eyeBrightness.x - smoothed.x) * factor;
        smoothed.y += (eyeBrightness.y - smoothed.y) * factor;

        // Rising toward rain uses the wetness half-life, falling back uses dryness — Iris's SmoothedFloat(up, down).
        float rainStrength = world.getRainStrength(CapturedRenderingState.INSTANCE.getTickDelta());
        float wetnessFactor =
                smoothingFactor(rainStrength > wetness ? wetnessHalfLife : drynessHalfLife, deltaSeconds);
        wetness += (rainStrength - wetness) * wetnessFactor;
    }

    public static Vector2i getEyeBrightness() {
        return new Vector2i(eyeBrightness);
    }

    public static Vector2i getEyeBrightnessSmooth() {
        return new Vector2i(Math.round(smoothed.x), Math.round(smoothed.y));
    }

    public static float getWetness() {
        return wetness;
    }
}
