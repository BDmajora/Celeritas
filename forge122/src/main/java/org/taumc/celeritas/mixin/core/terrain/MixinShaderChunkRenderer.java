package org.taumc.celeritas.mixin.core.terrain;

import org.embeddedt.embeddium.impl.gl.shader.GlProgram;
import org.embeddedt.embeddium.impl.render.chunk.ShaderChunkRenderer;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.taumc.celeritas.iris.terrain.IrisTerrainProgramOverride;

/**
 * When a shader pack is active, replaces Embeddium's default terrain program with the pack's transformed
 * {@code gbuffers_terrain} program, so chunk terrain is drawn by the shader pack (writing proper albedo/normal/light
 * into the gbuffer) instead of Embeddium's built-in block shader. Mirrors modern Iris's
 * {@code compat.sodium.mixin.shader_overrides.MixinShaderChunkRenderer}.
 * <p>
 * The actual replacement program is supplied by {@link IrisTerrainProgramOverride}; this mixin is only the interception
 * point on {@code compileProgram}. When no pack is active the override returns {@code null} and Embeddium's default is
 * used unchanged.
 */
@Mixin(ShaderChunkRenderer.class)
public class MixinShaderChunkRenderer {

    @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
    private void celeritas$overrideTerrainProgram(ChunkShaderOptions options,
                                                  CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> cir) {
        GlProgram<ChunkShaderInterface> override = IrisTerrainProgramOverride.getProgramOverride(options);
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
