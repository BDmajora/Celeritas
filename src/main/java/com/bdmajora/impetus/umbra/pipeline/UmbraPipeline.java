package com.bdmajora.impetus.umbra.pipeline;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.umbra.gl.program.UmbraProgram;
import com.bdmajora.impetus.umbra.gl.program.ShaderProgramCompiler;
import com.bdmajora.impetus.umbra.gl.shader.ShaderMacros;
import com.bdmajora.impetus.umbra.shaderpack.ProgramSource;
import com.bdmajora.impetus.umbra.shaderpack.ShaderPack;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL30;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The per-pack compile step: takes a parsed ShaderPack and builds every program it declares
// Must be constructed on the render thread with a GL context current, since compiling and linking are GL calls
// Everything is compiled up front rather than on first use, so a pack that will not build says so at load time
// instead of stuttering into a broken frame halfway through play
// Owns the compiled programs and their teardown; UmbraRenderingPipeline is the one that owns render targets,
// framebuffers and pass execution
public class UmbraPipeline {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    private final Map<String, UmbraProgram> programs = new LinkedHashMap<>();

    public UmbraPipeline(ShaderPack pack) {
        compilePrograms(pack);
    }

    private void compilePrograms(ShaderPack pack) {
        Map<String, String> defines = buildDefines(pack);
        Map<String, ProgramSource> declared = pack.getProgramSet().collectDeclaredPrograms();

        int compiled = 0;
        int failed = 0;

        for (Map.Entry<String, ProgramSource> entry : declared.entrySet()) {
            String name = entry.getKey();
            try {
                UmbraProgram program = ShaderProgramCompiler.compile(name, entry.getValue(), defines);
                this.programs.put(name, program);
                compiled++;
            } catch (Exception e) {
                failed++;
                LOGGER.error("Umbra: failed to compile program '{}': {}", name, e.getMessage());
            }
        }

    }

    private static Map<String, String> buildDefines(ShaderPack pack) {
        Map<String, String> defines = pack.getEnvironmentDefines();
        try {
            int major = LWJGL.glGetInteger(GL30.GL_MAJOR_VERSION);
            int minor = LWJGL.glGetInteger(GL30.GL_MINOR_VERSION);
            int glVersion = major * 100 + minor * 10;
            ShaderMacros.withGlInfo(defines, 120, glVersion,
                    LWJGL.glGetString(GL11.GL_VENDOR), LWJGL.glGetString(GL11.GL_RENDERER));
        } catch (Exception e) {
            // GL info is best-effort; a pack still compiles without the vendor/renderer macros.
            LOGGER.warn("Umbra: could not query GL info for shader macros: {}", e.getMessage());
        }
        return defines;
    }

    public UmbraProgram getProgram(String name) {
        return this.programs.get(name);
    }

    public int getCompiledProgramCount() {
        return this.programs.size();
    }

    // Frees every GL resource this owns. Render thread only, like the constructor — a GL delete from another
    // thread has no context and silently does nothing, leaking the program
    public void destroy() {
        for (UmbraProgram program : this.programs.values()) {
            program.destroy();
        }
        this.programs.clear();
    }
}
