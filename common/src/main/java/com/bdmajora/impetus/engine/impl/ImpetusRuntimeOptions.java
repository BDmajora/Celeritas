package com.bdmajora.impetus.engine.impl;

import com.bdmajora.impetus.engine.impl.gui.ImpetusGameOptions;

/**
 * Central, hot-readable snapshot of the option values that the render engine consults on hot paths (meshing,
 * translucency sorting, fluid rendering, entity sorting). The GUI writes user-facing values into
 * {@link ImpetusGameOptions}; {@link #apply(ImpetusGameOptions)} pushes the subset the engine reads per-frame
 * into plain static fields so those paths never touch config objects or synchronization.
 *
 * <p>Populated once at startup and again whenever options are applied.
 */
public final class ImpetusRuntimeOptions {
    private ImpetusRuntimeOptions() {
    }

    // Quality
    public static boolean improvedTransparency = false;
    public static boolean hiddenFluidCulling = true;
    public static boolean improvedFluidShaping = false;
    public static boolean closestPointEntitySort = false;

    // Performance
    public static boolean quadSplittingEnabled = true;
    public static ImpetusGameOptions.DeferChunkUpdatesMode deferMode = ImpetusGameOptions.DeferChunkUpdatesMode.ONE_FRAME;
    public static ImpetusGameOptions.InactivityFpsLimit inactivityFpsLimit = ImpetusGameOptions.InactivityFpsLimit.AFK;

    // Texture sampling (block atlas)
    public static ImpetusGameOptions.TextureFilteringMode textureFiltering = ImpetusGameOptions.TextureFilteringMode.DEFAULT;
    public static ImpetusGameOptions.PixelFilteringMode pixelFiltering = ImpetusGameOptions.PixelFilteringMode.NEAREST;
    /** Power-of-two exponent; 0 means anisotropy disabled. */
    public static int anisotropicFilteringBit = 0;

    public static void apply(ImpetusGameOptions options) {
        var quality = options.quality;
        improvedTransparency = quality.improvedTransparency;
        hiddenFluidCulling = quality.hiddenFluidCulling;
        improvedFluidShaping = quality.improvedFluidShaping;
        closestPointEntitySort = quality.closestPointEntitySort;
        textureFiltering = quality.textureFiltering;
        pixelFiltering = quality.pixelFiltering;
        anisotropicFilteringBit = quality.anisotropicFilteringBit;

        var performance = options.performance;
        quadSplittingEnabled = performance.quadSplittingMode.isEnabled();
        deferMode = performance.deferChunkUpdatesMode;
        inactivityFpsLimit = performance.inactivityFpsLimit;

        // Keep the legacy boolean the engine already reads in sync with the new tri-state defer mode.
        performance.alwaysDeferChunkUpdates = performance.deferChunkUpdatesMode.defersVisible();
    }

    /** {@return the maximum anisotropy factor to request, or 1.0 when disabled} */
    public static float anisotropyLevel() {
        return anisotropicFilteringBit <= 0 ? 1.0f : (float) (1 << anisotropicFilteringBit);
    }
}
