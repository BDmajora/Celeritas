package org.taumc.celeritas.mixin.core.terrain;

import org.embeddedt.embeddium.impl.gl.shader.GlProgram;
import org.embeddedt.embeddium.impl.render.chunk.ShaderChunkRenderer;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.taumc.celeritas.iris.terrain.IrisTerrainProgramOverride;

import java.util.Map;

/**
 * When a shader pack is active, replaces Embeddium's default terrain program with the pack's transformed
 * {@code gbuffers_terrain}/{@code gbuffers_water} program, so chunk terrain is drawn by the shader pack (writing
 * proper albedo/light into the gbuffer) instead of Embeddium's built-in block shader. Mirrors modern Iris's
 * {@code compat.sodium.mixin.shader_overrides.MixinShaderChunkRenderer}.
 * <p>
 * {@code compileProgram} is invoked on every {@code begin(pass)} — its own body is the cache lookup — so this
 * injection must participate in the {@code programs} map rather than cancel past it: the override is built once,
 * stored under the same key, and torn down by {@code ShaderChunkRenderer.delete()} with everything else. When the
 * override cannot be built the default path runs (and caches its program) so rendering never crashes.
 */
@Mixin(ShaderChunkRenderer.class)
public class MixinShaderChunkRenderer {

    @Shadow
    @Final
    private Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> programs;

    @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
    private void celeritas$overrideTerrainProgram(ChunkShaderOptions options,
                                                  CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> cir) {
        if (!IrisTerrainProgramOverride.areShadersActive()) {
            return;
        }
        if (org.taumc.celeritas.iris.pipeline.IrisShadowRenderer.isShadowPass()) {
            // Shadow programs live in their own cache (never in this renderer's map, which would poison the main
            // pass); a null return is handled by DefaultChunkRenderer's null-program guard (nothing drawn).
            cir.setReturnValue(IrisTerrainProgramOverride.getShadowProgramOverride(options));
            return;
        }
        if (this.programs.containsKey(options)) {
            // Already resolved (our override, or the default a failed build fell back to — possibly null).
            cir.setReturnValue(this.programs.get(options));
            return;
        }
        GlProgram<ChunkShaderInterface> override = IrisTerrainProgramOverride.getProgramOverride(options);
        if (override != null) {
            this.programs.put(options, override);
            cir.setReturnValue(override);
        }
    }
}
