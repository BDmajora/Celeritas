package com.bdmajora.impetus.engine.impl;

import com.bdmajora.impetus.engine.impl.gui.ImpetusGameOptions;

// A hot-readable snapshot of just the option values the render engine consults on hot paths: meshing,
// translucency sorting, fluid rendering, entity sorting
// The GUI writes user-facing values into ImpetusGameOptions; apply() then pushes the subset the engine reads
// per-frame into plain static fields
// The point is that those paths never touch a config object or take a lock — a mesh worker reading an option is a
// static field read and nothing more
// Populated once at startup and again on every Apply
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

    // Texture sampling (block atlas). Only magnification is configurable; minification is pinned to vanilla's
    // filter because the atlas has no border between sprites. See BlockAtlasFiltering.
    public static ImpetusGameOptions.PixelFilteringMode pixelFiltering = ImpetusGameOptions.PixelFilteringMode.NEAREST;

    public static void apply(ImpetusGameOptions options) {
        var quality = options.quality;
        improvedTransparency = quality.improvedTransparency;
        hiddenFluidCulling = quality.hiddenFluidCulling;
        improvedFluidShaping = quality.improvedFluidShaping;
        closestPointEntitySort = quality.closestPointEntitySort;
        pixelFiltering = quality.pixelFiltering;

        var performance = options.performance;
        quadSplittingEnabled = performance.quadSplittingMode.isEnabled();
        deferMode = performance.deferChunkUpdatesMode;
        inactivityFpsLimit = performance.inactivityFpsLimit;

        // Keep the legacy boolean the engine already reads in sync with the new tri-state defer mode.
        performance.alwaysDeferChunkUpdates = performance.deferChunkUpdatesMode.defersVisible();
    }
}
