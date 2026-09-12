package com.bdmajora.impetus.mixin.core.terrain;

import net.minecraft.client.renderer.ActiveRenderInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.FloatBuffer;

// Exposes the static projection/modelview buffers ActiveRenderInfo keeps private
@Mixin(ActiveRenderInfo.class)
public interface ActiveRenderInfoAccessor {
    // Accessor
    @Accessor("PROJECTION")
    static FloatBuffer getProjectionMatrix() {
        throw new AssertionError();
    }

    // Accessor
    @Accessor("MODELVIEW")
    static FloatBuffer getModelViewMatrix() {
        throw new AssertionError();
    }
}
