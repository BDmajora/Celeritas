package com.bdmajora.impetus.umbra.gl.program;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.umbra.gl.shader.GlShader;
import com.bdmajora.impetus.lwjgl.GL20;

import java.util.ArrayList;
import java.util.List;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Assembles a {@link GlProgram} from compiled {@link GlShader} stages: create the program object, attach the stages,
 * bind the OptiFine vertex-attribute slots ({@code mc_Entity}=10, {@code mc_midTexCoord}=11, {@code at_tangent}=12),
 * link, validate, then detach the stages.
 * <p>
 * This is the abstraction-layer equivalent of OptiFine's {@code Shaders.setupProgram}. Attribute binding must happen
 * before linking, which is why it is part of the builder rather than {@link GlProgram}.
 */
public class ProgramBuilder {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    private final String name;
    private final int program;
    private final List<GlShader> attached = new ArrayList<>();

    private ProgramBuilder(String name, int program) {
        this.name = name;
        this.program = program;
    }

    public static ProgramBuilder begin(String name) {
        int program = LWJGL.glCreateProgram();
        if (program == 0) {
            throw new ProgramCreationException("glCreateProgram returned 0 for program '" + name + "'");
        }
        return new ProgramBuilder(name, program);
    }

    public ProgramBuilder attach(GlShader shader) {
        LWJGL.glAttachShader(this.program, shader.getGlId());
        this.attached.add(shader);
        return this;
    }

    /** Binds a vertex attribute name to a fixed location. Must be called before {@link #link()}. */
    public ProgramBuilder bindAttributeLocation(int index, CharSequence attributeName) {
        LWJGL.glBindAttribLocation(this.program, index, attributeName);
        return this;
    }

    /** Binds a fragment output to a draw buffer index (GL3.0+). Must be called before {@link #link()}. */
    public ProgramBuilder bindFragmentDataLocation(int colorNumber, CharSequence outputName) {
        LWJGL.glBindFragDataLocation(this.program, colorNumber, outputName);
        return this;
    }

    /**
     * Links and validates the program. On success the attached stages are detached (the caller still owns them and is
     * expected to {@link GlShader#destroy()} them). On failure the program object is deleted and a
     * {@link ProgramCreationException} is thrown.
     */
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

    private void detachAll() {
        for (GlShader shader : this.attached) {
            if (!shader.isDestroyed()) {
                LWJGL.glDetachShader(this.program, shader.getGlId());
            }
        }
        this.attached.clear();
    }
}
