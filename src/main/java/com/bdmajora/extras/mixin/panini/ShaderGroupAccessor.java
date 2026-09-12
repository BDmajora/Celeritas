package com.bdmajora.extras.mixin.panini;

import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

// Exposes ShaderGroup's pass list; 1.12.2's post framework only sets uniforms to constants from JSON, and Panini's strength and extents change per frame with the FOV
@Mixin(ShaderGroup.class)
public interface ShaderGroupAccessor {
    @Accessor("listShaders")
    List<Shader> impetus$getShaders();
}
