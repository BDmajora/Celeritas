package com.bdmajora.extras.client;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig.RenderSettings;
import com.bdmajora.impetus.ImpetusVintage;
import net.minecraft.client.Minecraft;

// Cloud geometry values fed back into Impetus' cloud renderer; only scale lives here, since height and distance are Impetus' own Quality options consumed by SodiumCloudRenderer directly
public final class CloudPassState {
    // Vanilla's cloud cell edge length, in blocks.
    public static final float VANILLA_CELL_SIZE = 12.0F;

    private CloudPassState() {
    }

    // Cloud cell edge length after the scale option, stored in quarter steps (4 vanilla, 1 quarter, 16 four times); scaling the cell keeps one texel per cell so large scales do not blur
    public static float cellSize() {
        int scale = Math.max(RenderSettings.CLOUD_SCALE_MIN, Extras.options().render.cloudScale);
        return VANILLA_CELL_SIZE * scale / RenderSettings.CLOUD_SCALE_VANILLA;
    }

    // Altitude clouds are actually drawn at; NOT world.provider.getCloudHeight(), since Impetus overrides height at the RenderGlobalMixin call sites and the provider still reports the dimension default, and fallback covers before options exist
    public static float cloudHeight(float fallback) {
        if (Minecraft.getMinecraft().world == null) {
            return fallback;
        }
        return ImpetusVintage.options().quality.cloudHeight;
    }
}
