package com.bdmajora.impetus.umbra.uniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.joml.Vector2f;
import org.joml.Vector2i;

// eyeBrightness, eyeBrightnessSmooth and wetness smoothed with OptiFine's half-lives; smoothing advances exactly once per frame from the frame hook, never from a per-program supplier
public final class EyeBrightnessTracker {
    // Iris's defaults in DECISECONDS (SmoothedFloat multiplies by 0.1f): 60 seconds to get fully wet, 20 to dry, 1 second of eye-brightness smoothing; these were once hardcoded as ticks, halving every one so rain effects snapped on instead of fading
    private static final float DEFAULT_WETNESS_HALF_LIFE = 600.0f;
    private static final float DEFAULT_DRYNESS_HALF_LIFE = 200.0f;
    private static final float DEFAULT_EYE_BRIGHTNESS_HALF_LIFE = 10.0f;

    // Deciseconds, same unit as the defaults; overwritten on every pack load from the pack's own `const float *Halflife` directives
    private static volatile float wetnessHalfLife = DEFAULT_WETNESS_HALF_LIFE;
    private static volatile float drynessHalfLife = DEFAULT_DRYNESS_HALF_LIFE;
    private static volatile float eyeBrightnessHalfLife = DEFAULT_EYE_BRIGHTNESS_HALF_LIFE;

    private static final Vector2i eyeBrightness = new Vector2i();
    private static final Vector2f smoothed = new Vector2f();
    private static float wetness;
    private static long lastUpdateNanos = -1L;

    private EyeBrightnessTracker() {
    }

    // Installs the pack's wetnessHalflife, drynessHalflife and eyeBrightnessHalflife in deciseconds, once per pack load; non-positive means snap instantly, the natural limit of a zero half-life
    public static void setHalfLives(float wetnessDeciseconds, float drynessDeciseconds,
                                   float eyeBrightnessDeciseconds) {
        wetnessHalfLife = wetnessDeciseconds;
        drynessHalfLife = drynessDeciseconds;
        eyeBrightnessHalfLife = eyeBrightnessDeciseconds;
    }

    // The exponential-smoothing blend factor for one frame, so half the remaining distance is covered every halfLifeDeciseconds; derived from frame time so the rate matches at 30 and 200 fps (Iris's 1 - e^(-kt) with k = ln2 / (halfLife * 0.1))
    private static float smoothingFactor(float halfLifeDeciseconds, float deltaSeconds) {
        if (halfLifeDeciseconds <= 0.0f) {
            return 1.0f;
        }
        return 1.0f - (float) Math.pow(0.5, deltaSeconds / (halfLifeDeciseconds * 0.1f));
    }

    // Advances the tracker one frame from the pipeline's frame-begin hook, exactly once, the property the whole class depends on
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
        // OptiFine reads this straight off the entity (entity.getBrightnessForRender()), and so do we; the hand-rolled world.getCombinedLight it replaces reported (0,0) in open daylight and latched Complementary's light shafts into cave mode, since getBrightnessForRender applies the isBlockLoaded guard and Y clamp
        int combined = camera.getBrightnessForRender();
        eyeBrightness.set(combined & 0xFFFF, combined >> 16);

        float factor = smoothingFactor(eyeBrightnessHalfLife, deltaSeconds);
        smoothed.x += (eyeBrightness.x - smoothed.x) * factor;
        smoothed.y += (eyeBrightness.y - smoothed.y) * factor;

        // Rising toward rain uses the wetness half-life, falling back uses dryness — Umbra's SmoothedFloat(up, down).
        float rainStrength = world.getRainStrength(CapturedRenderingState.INSTANCE.getTickDelta());
        float wetnessFactor =
                smoothingFactor(rainStrength > wetness ? wetnessHalfLife : drynessHalfLife, deltaSeconds);
        wetness += (rainStrength - wetness) * wetnessFactor;
    }

    // Raw block and sky light at the eye, 0..240
    public static Vector2i getEyeBrightness() {
        return new Vector2i(eyeBrightness);
    }

    // Smoothed with OptiFine's eyeBrightnessHalflife
    public static Vector2i getEyeBrightnessSmooth() {
        return new Vector2i(Math.round(smoothed.x), Math.round(smoothed.y));
    }

    // Smoothed rain exposure, for wet surfaces
    public static float getWetness() {
        return wetness;
    }
}
