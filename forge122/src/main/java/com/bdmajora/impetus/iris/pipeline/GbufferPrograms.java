package com.bdmajora.impetus.iris.pipeline;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.gl.program.DrawBuffers;
import com.bdmajora.impetus.iris.gl.program.GlProgram;
import com.bdmajora.impetus.iris.gl.program.IrisProgram;
import com.bdmajora.impetus.iris.gl.program.ProgramUniforms;
import com.bdmajora.impetus.iris.gl.program.ShaderProgramCompiler;
import com.bdmajora.impetus.iris.gl.shader.ShaderMacros;
import com.bdmajora.impetus.iris.gl.blending.ProgramBlendState;
import com.bdmajora.impetus.iris.shaderpack.ProgramSource;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.iris.uniforms.CommonUniforms;
import com.bdmajora.impetus.iris.uniforms.MatrixUniforms;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The pack's fixed-function gbuffer programs — sky, entities, block damage, particles, weather, clouds, hand. These
 * vanilla sections still render through the classic fixed-function pipeline (immediate mode / client arrays), and on
 * our compatibility-profile context the pack's original GLSL-120 programs consume that state natively
 * ({@code gl_Vertex}, {@code gl_ModelViewProjectionMatrix}, {@code gl_MultiTexCoord0}, …). So, exactly like OptiFine,
 * each program is compiled <em>untransformed</em> and simply bound around its vanilla render section; only Impetus
 * terrain (custom vertex format) and the fullscreen passes (core-profile quad) need GLSL transformation.
 * <p>
 * OptiFine's fallback chain is honored via {@link com.bdmajora.impetus.iris.shaderpack.ProgramSet#get(ProgramId)}, and
 * phases resolving to the same source share one compiled program.
 */
public class GbufferPrograms {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    /** The phases driven from the vanilla render loop anchors in {@code EntityRendererMixin}. */
    private static final ProgramId[] PHASES = {
            ProgramId.SkyBasic, ProgramId.SkyTextured, ProgramId.Entities, ProgramId.DamagedBlock,
            ProgramId.TexturedLit, ProgramId.Weather, ProgramId.Clouds, ProgramId.Hand
    };

    /** One compiled gbuffer program plus its uniform driver and (sanitized) DRAWBUFFERS mask. */
    public static final class Entry {
        final IrisProgram program;
        final ProgramUniforms uniforms;
        final int[] drawBuffers;
        final ProgramBlendState blendState;

        Entry(IrisProgram program, ProgramUniforms uniforms, int[] drawBuffers, ProgramBlendState blendState) {
            this.program = program;
            this.uniforms = uniforms;
            this.drawBuffers = drawBuffers == null ? DrawBuffers.DEFAULT.clone() : drawBuffers.clone();
            this.blendState = blendState;
        }

        public IrisProgram getProgram() {
            return this.program;
        }

        public ProgramUniforms getUniforms() {
            return this.uniforms;
        }

        public int[] getDrawBuffers() {
            return this.drawBuffers.clone();
        }

        public ProgramBlendState getBlendState() {
            return this.blendState;
        }
    }

    private final Map<ProgramId, Entry> byPhase = new EnumMap<>(ProgramId.class);
    private final List<Entry> ownedEntries = new ArrayList<>();

    /**
     * @param samplerOverrides the pack's gbuffers-stage custom-texture units (sampler name → dedicated unit), applied
     *                         over the standard table so e.g. {@code texture.gbuffers.gaux4} redirects that sampler.
     */
    GbufferPrograms(ShaderPack pack, Map<String, Integer> samplerUnits, Map<String, Integer> samplerOverrides) {
        Map<String, String> defines = ShaderMacros.standard();
        // Fixed-function stages sample the bound atlas/lightmap on the vanilla units, plus OptiFine's aux slots.
        Map<String, Integer> gbufferSamplers = new HashMap<>(samplerUnits);
        gbufferSamplers.put("texture", 0);
        gbufferSamplers.put("gtexture", 0);
        gbufferSamplers.put("lightmap", 1);
        gbufferSamplers.put("normals", 2);
        gbufferSamplers.put("specular", 3);
        // Custom-texture overrides win over everything, including the vanilla-unit additions above.
        gbufferSamplers.putAll(samplerOverrides);

        Map<String, Entry> bySourceName = new HashMap<>();
        for (ProgramId phase : PHASES) {
            Optional<ProgramSource> source = pack.getProgramSet().get(phase);
            if (!source.isPresent()) {
                continue;
            }
            String sourceName = source.get().getName();
            Entry entry;
            if (bySourceName.containsKey(sourceName)) {
                entry = bySourceName.get(sourceName); // may be null: a failed compile is not retried
            } else {
                entry = compile(source.get(), defines, gbufferSamplers,
                        ProgramBlendState.from(pack.getProperties(), sourceName));
                bySourceName.put(sourceName, entry);
                if (entry != null) {
                    this.ownedEntries.add(entry);
                }
            }
            if (entry != null) {
                this.byPhase.put(phase, entry);
                LOGGER.info("[Iris] Gbuffer phase {} -> '{}'", phase, sourceName);
            }
        }
    }

    /** Also used by {@link IrisShadowRenderer} to compile the fixed-function flavor of the {@code shadow} program. */
    static Entry compile(ProgramSource source, Map<String, String> defines, Map<String, Integer> samplerUnits) {
        return compile(source, defines, samplerUnits, ProgramBlendState.empty());
    }

    static Entry compile(ProgramSource source, Map<String, String> defines, Map<String, Integer> samplerUnits,
                         ProgramBlendState blendState) {
        try {
            IrisProgram program = ShaderProgramCompiler.compile(source.getName(), source, defines);

            GlProgram glProgram = program.getProgram();
            glProgram.bind();
            for (Map.Entry<String, Integer> sampler : samplerUnits.entrySet()) {
                int location = glProgram.getUniformLocation(sampler.getKey());
                if (location != -1) {
                    LWJGL.glUniform1i(location, sampler.getValue());
                }
            }
            glProgram.unbind();

            ProgramUniforms.Builder builder = ProgramUniforms.builder(source.getName(), glProgram.getGlId());
            CommonUniforms.addCommonUniforms(builder);
            MatrixUniforms.addMatrixUniforms(builder);
            int[] drawBuffers = IrisRenderingPipeline.sanitizeDrawBuffers(source.getName(), program.getDrawBuffers());
            return new Entry(program, builder.buildUniforms(), drawBuffers, blendState);
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to compile gbuffer program '{}'; its phases render vanilla-style: {}",
                    source.getName(), e.getMessage());
            return null;
        }
    }

    /** @return the compiled program for a phase, or {@code null} when the pack has none (render fixed-function). */
    public Entry get(ProgramId phase) {
        return this.byPhase.get(phase);
    }

    /** Every distinct compiled entry (for draw-buffer union / teardown). */
    public List<Entry> entries() {
        return this.ownedEntries;
    }

    public void destroy() {
        for (Entry entry : this.ownedEntries) {
            entry.program.destroy();
        }
        this.ownedEntries.clear();
        this.byPhase.clear();
    }
}
