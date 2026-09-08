package com.bdmajora.dynamiclights.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens up {@code RenderGlobal#markBlocksForUpdate} so a light source can re-light its own chunks.
 *
 * <p>Impetus {@code @Overwrite}s that method to route into its own chunk renderer
 * ({@code core/terrain/RenderGlobalMixin}), so calling it here schedules an Impetus section rebuild
 * rather than a vanilla one — which is exactly what is wanted, and why this goes through the vanilla
 * entry point instead of reaching for the renderer directly.
 */
@Mixin(RenderGlobal.class)
public interface RenderGlobalRebuildAccessor {
    @Invoker("markBlocksForUpdate")
    void impetus$markBlocksForUpdate(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                     boolean updateImmediately);
}
