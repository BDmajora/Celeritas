package com.bdmajora.impetus.iris.gl.program;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.gl.shader.GlShader;
import com.bdmajora.impetus.iris.gl.shader.ShaderType;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import com.bdmajora.impetus.iris.shaderpack.ProgramSource;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.GlslPreprocessor;
import com.bdmajora.impetus.iris.targets.IrisRenderTargets;
import com.bdmajora.impetus.iris.terrain.ModernPackTransformer;
import com.bdmajora.impetus.iris.vertices.IrisVertexAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Compiles a parsed {@link ProgramSource} (from Phase 1) into a linked {@link IrisProgram}, applying the shared
 * {@code #define} set and binding the OptiFine vertex-attribute slots. This is the bridge between the shader-pack model
 * and the GL layer — the concrete realisation of the Phase 2 milestone "programs compile".
 * <p>
 * Must be called on the render thread (it issues GL calls). The caller is expected to catch
 * {@link com.bdmajora.impetus.iris.gl.shader.ShaderCompileException} / {@link ProgramCreationException} and disable
 * shaders on failure rather than crash.
 */
public final class ShaderProgramCompiler {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    private ShaderProgramCompiler() {
    }

    public static IrisProgram compile(String name, ProgramSource source, Map<String, String> defines) {
        String vertexSource = source.getVertexSource().orElse(null);
        String fragmentSource = source.getFragmentSource().orElse(null);
        String geometrySource = source.getGeometrySource().orElse(null);

        if (vertexSource == null || fragmentSource == null) {
            throw new ProgramCreationException("Program '" + name + "' is missing a vertex or fragment stage");
        }

        // Modern packs (#version 130+ single-source dual-stage, e.g. Complementary) need the #version bumped to
        // "330 compatibility" to compile on the 1.12.2 compat context — exactly like the fullscreen/terrain modern
        // paths. Without this these gbuffer programs fail to compile and their phases fall back to vanilla-style
        // rendering; for gbuffers_clouds that means drawing the vanilla cloud plane the pack explicitly discards
        // (gl_Position = vec4(-1.0) + discard when CLOUD_STYLE != 50), which is the "clouds move with the player" bug.
        // GLSL-120 packs (LIGHT) do not need the version transform, but they still share the same dense draw-buffer
        // routing as modern packs.
        int[] drawBuffers = DrawBuffers.sanitize(
                DrawBuffers.parseActive(fragmentSource, defines), IrisRenderTargets.MAX_COLOR_BUFFERS);
        if (ModernPackTransformer.isModernSource(fragmentSource)) {
            vertexSource = ModernPackTransformer.transform(vertexSource);
            fragmentSource = ModernPackTransformer.transform(fragmentSource);
            if (geometrySource != null) {
                geometrySource = ModernPackTransformer.transform(geometrySource);
            }
        }
        fragmentSource = DrawBuffers.rewriteFragmentOutputs(fragmentSource, drawBuffers);

        String processedVertex = IrisRenderingPipeline.stabilizeShaderSource(name,
                applyDefines(vertexSource, defines));
        String processedFragment = IrisRenderingPipeline.stabilizeShaderSource(name,
                applyDefines(fragmentSource, defines));

        GlShader vertexShader = null;
        GlShader fragmentShader = null;
        GlShader geometryShader = null;
        try {
            vertexShader = new GlShader(ShaderType.VERTEX, name + ".vsh", processedVertex);
            fragmentShader = new GlShader(ShaderType.FRAGMENT, name + ".fsh", processedFragment);

            ProgramBuilder builder = ProgramBuilder.begin(name)
                    .attach(vertexShader)
                    .attach(fragmentShader);

            if (geometrySource != null) {
                geometryShader = new GlShader(ShaderType.GEOMETRY, name + ".gsh",
                        IrisRenderingPipeline.stabilizeShaderSource(name,
                                applyDefines(geometrySource, defines)));
                builder.attach(geometryShader);
            }

            bindOptifineAttributes(builder, vertexSource);

            GlProgram program = builder.link();
            LOGGER.info("[Iris] {} resolved DRAWBUFFERS {}", name, Arrays.toString(drawBuffers));
            return new IrisProgram(program, drawBuffers);
        } finally {
            // The stage objects are no longer needed once the program is linked (or if linking failed).
            if (vertexShader != null) {
                vertexShader.destroy();
            }
            if (fragmentShader != null) {
                fragmentShader.destroy();
            }
            if (geometryShader != null) {
                geometryShader.destroy();
            }
        }
    }

    private static void bindOptifineAttributes(ProgramBuilder builder, String vertexSource) {
        // Only bind slots for attributes the vertex shader actually declares, matching OptiFine's setupProgram.
        if (declaresAttribute(vertexSource, IrisVertexAttributes.MC_ENTITY)) {
            builder.bindAttributeLocation(IrisVertexAttributes.MC_ENTITY_SLOT, IrisVertexAttributes.MC_ENTITY);
        }
        if (declaresAttribute(vertexSource, IrisVertexAttributes.MC_MID_TEX_COORD)) {
            builder.bindAttributeLocation(IrisVertexAttributes.MC_MID_TEX_COORD_SLOT, IrisVertexAttributes.MC_MID_TEX_COORD);
        }
        if (declaresAttribute(vertexSource, IrisVertexAttributes.AT_TANGENT)) {
            builder.bindAttributeLocation(IrisVertexAttributes.AT_TANGENT_SLOT, IrisVertexAttributes.AT_TANGENT);
        }
    }

    private static boolean declaresAttribute(String source, String attributeName) {
        // Matches OptiFine's `attribute <type> <name>` scan, tolerant of both GLSL 120 `attribute` and 150 `in`.
        return source.matches("(?s).*\\b(?:attribute|in)\\s+\\w+\\s+" + attributeName + "\\b.*");
    }

    private static String applyDefines(String source, Map<String, String> defines) {
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        List<String> processed = GlslPreprocessor.injectDefines(lines, defines);
        return String.join("\n", processed);
    }
}
