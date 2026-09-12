package com.bdmajora.impetus.umbra.gl.program;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.umbra.gl.shader.GlShader;
import com.bdmajora.impetus.lwjgl.GL20;

import java.util.ArrayList;
import java.util.List;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// Assembles a GlProgram from compiled GlShader stages: create the program object, attach the stages, bind the
// OptiFine vertex-attribute slots (mc_Entity 10, mc_midTexCoord 11, at_tangent 12), link, validate, detach
// The abstraction-layer equivalent of OptiFine's Shaders.setupProgram
// It is a builder rather than methods on GlProgram because attribute binding has to happen BEFORE linking, so
// there is a window where the program object exists but is not yet a usable GlProgram
public class ProgramBuilder {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    private final String name;
    private final int program;
    private final List<GlShader> attached = new ArrayList<>();

    private ProgramBuilder(String name, int program) {
        this.name = name;
        this.program = program;
    }

    // Creates the program object
    public static ProgramBuilder begin(String name) {
        int program = LWJGL.glCreateProgram();
        if (program == 0) {
            throw new ProgramCreationException("glCreateProgram returned 0 for program '" + name + "'");
        }
        return new ProgramBuilder(name, program);
    }

    // glAttachShader
    public ProgramBuilder attach(GlShader shader) {
        LWJGL.glAttachShader(this.program, shader.getGlId());
        this.attached.add(shader);
        return this;
    }

    // Binds a vertex attribute name to a fixed location. Before link() — glBindAttribLocation only takes effect at
    // the next link, so calling it afterwards silently does nothing
    public ProgramBuilder bindAttributeLocation(int index, CharSequence attributeName) {
        LWJGL.glBindAttribLocation(this.program, index, attributeName);
        return this;
    }

    // Binds a fragment output to a draw buffer index (GL 3.0+). Before link(), for the same reason
    public ProgramBuilder bindFragmentDataLocation(int colorNumber, CharSequence outputName) {
        LWJGL.glBindFragDataLocation(this.program, colorNumber, outputName);
        return this;
    }

    // Links and validates
    // On success the stages are DETACHED but not deleted — the caller still owns them and is expected to destroy
    // them, since one compiled stage is often attached to several programs
    // On failure the program object is deleted before throwing, so a rejected link leaks nothing
    public GlProgram link() {
        LWJGL.glLinkProgram(this.program);

        int linkStatus = LWJGL.glGetProgrami(this.program, GL20.GL_LINK_STATUS);
        String log = LWJGL.glGetProgramInfoLog(this.program, 32768);

        if (linkStatus != GL20.GL_TRUE) {
            detachAll();
            LWJGL.glDeleteProgram(this.program);
            throw new ProgramCreationException("Failed to link program '" + this.name + "': "
                    + (log.isEmpty() ? "(no info log)" : log.trim()));
        }

        // NVIDIA returns a wall of deprecation warnings in the link log for legacy #version 120 packs; only surface
        // the log when it actually reports an error, otherwise it's just noise.
        if (!log.isEmpty() && log.toLowerCase(java.util.Locale.ROOT).contains("error")) {
            LOGGER.warn("Program link log for '{}': {}", this.name, log.trim());
        }

        detachAll();
        return new GlProgram(this.program, this.name);
    }

    // Detaches every stage after linking, so the shader objects can be deleted
    private void detachAll() {
        for (GlShader shader : this.attached) {
            if (!shader.isDestroyed()) {
                LWJGL.glDetachShader(this.program, shader.getGlId());
            }
        }
        this.attached.clear();
    }
}
