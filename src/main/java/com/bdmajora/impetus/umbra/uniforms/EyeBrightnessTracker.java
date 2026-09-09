package com.bdmajora.impetus.umbra.uniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.joml.Vector2f;
import org.joml.Vector2i;

// The temporally smoothed per-frame uniforms
// eyeBrightness and eyeBrightnessSmooth are the lightmap coordinates at the camera's eye — block and sky, each
// 0..240 — which packs use for the exposure fade when walking into a cave
// wetness is rain strength smoothed with OptiFine's separate wetness and dryness half-lives, so getting wet is slow
// and drying off is slower
// The smoothing must advance exactly ONCE per frame, from update() on the frame hook — never from the uniform
// suppliers, which run once per program and would advance it a dozen times a frame at a dozen different rates
public final class EyeBrightnessTracker {
    // Iris's defaults, in DECISECONDS — the unit its SmoothedFloat takes, which multiplies by 0.1f to reach
    // seconds. So the shipped values mean 60 seconds to get fully wet, 20 to dry off, and 1 second of
    // eye-brightness smoothing
    // These were previously hardcoded AND interpreted as ticks (halfLife / 20), which halved every one of them to
    // 30s/10s/0.5s. Packs are tuned against the Iris and OptiFine rates, so a doubled rate is visible as rain
    // effects that snap on instead of fading
    private static final float DEFAULT_WETNESS_HALF_LIFE = 600.0f;
    private static final float DEFAULT_DRYNESS_HALF_LIFE = 200.0f;
    private static final float DEFAULT_EYE_BRIGHTNESS_HALF_LIFE = 10.0f;

    // Deciseconds, same unit as the defaults. Overwritten on every pack load from the pack's own
    // `const float *Halflife` directives, so a pack that tunes these gets its own rates
    private static volatile float wetnessHalfLife = DEFAULT_WETNESS_HALF_LIFE;
    private static volatile float drynessHalfLife = DEFAULT_DRYNESS_HALF_LIFE;
    private static volatile float eyeBrightnessHalfLife = DEFAULT_EYE_BRIGHTNESS_HALF_LIFE;

    private static final Vector2i eyeBrightness = new Vector2i();
    private static final Vector2f smoothed = new Vector2f();
    private static float wetness;
    private static long lastUpdateNanos = -1L;

    private EyeBrightnessTracker() {
    }

    // Installs the pack's own wetnessHalflife, drynessHalflife and eyeBrightnessHalflife, in deciseconds
    // Once per pack load. A non-positive value means snap instantly, which is the natural limit a half-life of zero
    // degenerates to rather than a special case
    public static void setHalfLives(float wetnessDeciseconds, float drynessDeciseconds,
                                   float eyeBrightnessDeciseconds) {
        wetnessHalfLife = wetnessDeciseconds;
        drynessHalfLife = drynessDeciseconds;
        eyeBrightnessHalfLife = eyeBrightnessDeciseconds;
    }

    // The exponential-smoothing blend factor for one frame: how far to move toward the target so that half the
    // remaining distance is covered every halfLifeDeciseconds
    // Derived from the frame time rather than assumed constant, so the smoothing rate is the same at 30 and 200 fps
    // Iris writes the identical thing as 1 - e^(-kt) with k = ln2 / (halfLife * 0.1)
    private static float smoothingFactor(float halfLifeDeciseconds, float deltaSeconds) {
        if (halfLifeDeciseconds <= 0.0f) {
            return 1.0f;
        }
        return 1.0f - (float) Math.pow(0.5, deltaSeconds / (halfLifeDeciseconds * 0.1f));
    }

    // Advances the tracker one frame, from the pipeline's frame-begin hook on the render thread — exactly once,
    // which is the property the whole class depends on
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

        // Rising toward rain uses the wetness half-life, falling back uses dryness — Umbra's SmoothedFloat(up, down).
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
