package com.bdmajora.impetus.mixin.core.terrain;

import com.bdmajora.impetus.engine.impl.gl.shader.GlProgram;
import com.bdmajora.impetus.engine.impl.render.chunk.ShaderChunkRenderer;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderInterface;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.bdmajora.impetus.iris.terrain.IrisTerrainProgramOverride;

import java.util.Map;

/** Swaps terrain programs while shader packs are active. */
@Mixin(ShaderChunkRenderer.class)
public class MixinShaderChunkRenderer {

    @Shadow
    @Final
    private Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> programs;

    @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
    private void impetus$overrideTerrainProgram(ChunkShaderOptions options,
                                                  CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> cir) {
        if (!IrisTerrainProgramOverride.areShadersActive()) {
            return;
        }
        if (com.bdmajora.impetus.iris.pipeline.IrisShadowRenderer.isShadowPass()) {
            // Shadow programs use their own cache.
            cir.setReturnValue(IrisTerrainProgramOverride.getShadowProgramOverride(options));
            return;
        }
        if (this.programs.containsKey(options)) {
            // Reuse already resolved overrides.
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
