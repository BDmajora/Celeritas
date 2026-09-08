package com.bdmajora.extras.client;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig.RenderSettings;
import com.bdmajora.impetus.ImpetusVintage;
import net.minecraft.client.Minecraft;

/**
 * Cloud geometry values the Extras options feed back into Impetus' own cloud renderer.
 *
 * <p>Only scale lives here. Cloud height and distance are Impetus' own Quality options, consumed by
 * {@code SodiumCloudRenderer} directly, and duplicating them into Extras would leave two sliders
 * fighting over one value.
 */
public final class CloudPassState {
    /** Vanilla's cloud cell edge length, in blocks. */
    public static final float VANILLA_CELL_SIZE = 12.0F;

    private CloudPassState() {
    }

    /**
     * The cloud cell edge length in blocks after the scale option.
     *
     * <p>Scale is stored in quarter steps so it can be an integer slider: 4 is vanilla, 1 is a
     * quarter-size cell, 16 is four times. Scaling the cell rather than the mesh keeps the cloud
     * texture mapped one texel per cell, which is what stops large scales from blurring.
     */
    public static float cellSize() {
        int scale = Math.max(RenderSettings.CLOUD_SCALE_MIN, Extras.options().render.cloudScale);
        return VANILLA_CELL_SIZE * scale / RenderSettings.CLOUD_SCALE_VANILLA;
    }

    /**
     * The altitude clouds are actually drawn at.
     *
     * <p>Deliberately <em>not</em> {@code world.provider.getCloudHeight()}. Impetus overrides the
     * cloud height at the two call sites in {@code RenderGlobalMixin} rather than on the provider,
     * so the provider still reports the dimension default — asking it would put the translucency
     * threshold at 128 while the clouds themselves sit wherever the Quality slider says.
     *
     * @param fallback used before the options exist, so callers never see a meaningless zero
     */
    public static float cloudHeight(float fallback) {
        if (Minecraft.getMinecraft().world == null) {
            return fallback;
        }
        return ImpetusVintage.options().quality.cloudHeight;
    }
}
