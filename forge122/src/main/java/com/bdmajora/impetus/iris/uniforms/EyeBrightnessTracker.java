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
    /** OptiFine defaults, in ticks: it takes ~30s of rain to get fully wet and ~10s to dry off. */
    private static final float WETNESS_HALF_LIFE_TICKS = 600.0f;
    private static final float DRYNESS_HALF_LIFE_TICKS = 200.0f;

    private static final Vector2i eyeBrightness = new Vector2i();
    private static final Vector2f smoothed = new Vector2f();
    private static float wetness;
    private static long lastUpdateNanos = -1L;

    private EyeBrightnessTracker() {
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

        // Exponential approach with a ~0.5s half-life — matches the feel of OptiFine's smoothing.
        float factor = 1.0f - (float) Math.pow(0.5, deltaSeconds * 2.0);
        smoothed.x += (eyeBrightness.x - smoothed.x) * factor;
        smoothed.y += (eyeBrightness.y - smoothed.y) * factor;

        float rainStrength = world.getRainStrength(CapturedRenderingState.INSTANCE.getTickDelta());
        float halfLifeTicks = rainStrength > wetness ? WETNESS_HALF_LIFE_TICKS : DRYNESS_HALF_LIFE_TICKS;
        float wetnessFactor = 1.0f - (float) Math.pow(0.5, deltaSeconds * 20.0f / halfLifeTicks);
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
