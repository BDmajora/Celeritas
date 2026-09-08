package com.bdmajora.extras.mixin.panini;

import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes {@code ShaderGroup}'s pass list.
 *
 * <p>1.12.2's post-processing framework can set uniforms from the chain JSON, but only to constants:
 * there is no public way to reach a pass and write a value into it per frame. Panini needs exactly
 * that — its strength and the projection extents change with the FOV — so the list has to be opened
 * up.
 */
@Mixin(ShaderGroup.class)
public interface ShaderGroupAccessor {
    @Accessor("listShaders")
    List<Shader> impetus$getShaders();
}
