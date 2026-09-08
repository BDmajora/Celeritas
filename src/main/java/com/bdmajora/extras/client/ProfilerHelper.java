package com.bdmajora.extras.client;

import com.bdmajora.extras.Extras;
import net.minecraft.world.World;

import java.util.Map;
import java.util.WeakHashMap;

// Names profiler sections after the renderer, so the F3 pie chart splits entity/block-entity
// rendering by type instead of one slice
// Push/pop must stay balanced or the profiler throws, so both ends run the identical
// shouldProfile check rather than one deciding and the other assuming
public final class ProfilerHelper {
    private static final Map<Class<?>, String> NAME_CACHE = new WeakHashMap<>();

    private ProfilerHelper() {
    }

    // Pushes a section named after the renderer's simple class name
    public static void startSection(World world, Object renderer) {
        if (shouldProfile(world, renderer)) {
            world.profiler.startSection(sectionName(renderer));
        }
    }

    // Pops the section startSection pushed for this renderer
    public static void endSection(World world, Object renderer) {
        if (shouldProfile(world, renderer)) {
            world.profiler.endSection();
        }
    }

    private static boolean shouldProfile(World world, Object renderer) {
        return world != null
                && renderer != null
                && Extras.options().render.profileEntityRendering
                && !sectionName(renderer).isEmpty();
    }

    private static String sectionName(Object renderer) {
        return NAME_CACHE.computeIfAbsent(renderer.getClass(), Class::getSimpleName);
    }
}
