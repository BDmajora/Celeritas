package org.taumc.celeritas.iris.terrain;

import com.l.ausm.impl.MainMod;
import com.l.ausm.impl.pipeline.pack.ShaderPackManager;
import org.embeddedt.embeddium.impl.gl.shader.GlProgram;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderOptions;

/**
 * Decides whether Embeddium's default terrain program should be replaced by the shader pack's transformed
 * {@code gbuffers_terrain} program for a given render pass. This is the seam that {@code MixinShaderChunkRenderer}
 * calls from {@code ShaderChunkRenderer.compileProgram} — the 1.12.2 analogue of modern Iris's
 * {@code IrisChunkProgramOverrides}.
 * <p>
 * <b>Piece 1 (this):</b> the override hook + activation gate. It returns {@code null} (keep Embeddium's default) until
 * the program builder + Iris {@link ChunkShaderInterface} (piece 2) exist; a program is only ever substituted while a
 * pack is enabled, so terrain rendering is unchanged when shaders are off.
 */
public final class IrisTerrainProgramOverride {
    private IrisTerrainProgramOverride() {
    }

    /**
     * @return the replacement terrain {@link GlProgram} to use for {@code options}, or {@code null} to let Embeddium
     * compile its own default terrain shader.
     */
    public static GlProgram<ChunkShaderInterface> getProgramOverride(ChunkShaderOptions options) {
        if (!areShadersActive()) {
            return null;
        }
        // TODO (piece 2): build (and cache) the transformed pack gbuffers_terrain program wrapped in an Iris
        // ChunkShaderInterface that binds the gbuffer framebuffer + uniforms. Until then, fall through to Embeddium's
        // default so terrain still renders (just unshaded), keeping this piece safe to ship on its own.
        return null;
    }

    public static boolean areShadersActive() {
        ShaderPackManager manager = MainMod.getShaderPackManager();
        return manager != null && manager.areShadersEnabled();
    }
}
