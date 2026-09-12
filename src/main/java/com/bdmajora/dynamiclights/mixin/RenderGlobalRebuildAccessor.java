package com.bdmajora.dynamiclights.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Opens RenderGlobal#markBlocksForUpdate so a source can re-light its chunks; Impetus @Overwrites it to route into its own renderer, so this schedules an Impetus rebuild through the vanilla entry point
@Mixin(RenderGlobal.class)
public interface RenderGlobalRebuildAccessor {
    @Invoker("markBlocksForUpdate")
    void impetus$markBlocksForUpdate(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                     boolean updateImmediately);
}
