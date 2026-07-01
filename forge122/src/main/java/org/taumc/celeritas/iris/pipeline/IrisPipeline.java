package org.taumc.celeritas.iris.pipeline;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.taumc.celeritas.iris.gl.program.IrisProgram;
import org.taumc.celeritas.iris.gl.program.ShaderProgramCompiler;
import org.taumc.celeritas.iris.gl.shader.ShaderMacros;
import org.taumc.celeritas.iris.shaderpack.ProgramSource;
import org.taumc.celeritas.iris.shaderpack.ShaderPack;
import org.taumc.celeritas.lwjgl.GL11;
import org.taumc.celeritas.lwjgl.GL30;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The per-shader-pack rendering pipeline. Constructed on the render thread (a GL context must be current) from a parsed
 * {@link ShaderPack}: it compiles every declared program up front so the user gets an immediate, honest log of what
 * built and what did not — the tangible Phase 2 milestone.
 * <p>
 * Later phases grow this class into the full controller (render targets, per-pass framebuffers, shadow/composite
 * execution). For now it owns the compiled {@link IrisProgram}s and their teardown.
 */
public class IrisPipeline {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Iris");

    private final Map<String, IrisProgram> programs = new LinkedHashMap<>();

    public IrisPipeline(ShaderPack pack) {
        compilePrograms(pack);
    }

    private void compilePrograms(ShaderPack pack) {
        Map<String, String> defines = buildDefines();
        Map<String, ProgramSource> declared = pack.getProgramSet().collectDeclaredPrograms();

        int compiled = 0;
        int failed = 0;

        for (Map.Entry<String, ProgramSource> entry : declared.entrySet()) {
            String name = entry.getKey();
            try {
                IrisProgram program = ShaderProgramCompiler.compile(name, entry.getValue(), defines);
                this.programs.put(name, program);
                compiled++;
            } catch (Exception e) {
                failed++;
                LOGGER.error("Iris: failed to compile program '{}': {}", name, e.getMessage());
            }
        }

        LOGGER.info("Iris: compiled {} of {} shader program(s){}",
                compiled, declared.size(), failed > 0 ? " (" + failed + " failed — see errors above)" : "");
    }

    private static Map<String, String> buildDefines() {
        Map<String, String> defines = ShaderMacros.standard();
        try {
            int major = LWJGL.glGetInteger(GL30.GL_MAJOR_VERSION);
            int minor = LWJGL.glGetInteger(GL30.GL_MINOR_VERSION);
            int glVersion = major * 100 + minor * 10;
            ShaderMacros.withGlInfo(defines, 120, glVersion,
                    LWJGL.glGetString(GL11.GL_VENDOR), LWJGL.glGetString(GL11.GL_RENDERER));
        } catch (Exception e) {
            // GL info is best-effort; a pack still compiles without the vendor/renderer macros.
            LOGGER.warn("Iris: could not query GL info for shader macros: {}", e.getMessage());
        }
        return defines;
    }

    public IrisProgram getProgram(String name) {
        return this.programs.get(name);
    }

    public int getCompiledProgramCount() {
        return this.programs.size();
    }

    /** Frees all GL resources owned by this pipeline. Must be called on the render thread. */
    public void destroy() {
        for (IrisProgram program : this.programs.values()) {
            program.destroy();
        }
        this.programs.clear();
    }
}
