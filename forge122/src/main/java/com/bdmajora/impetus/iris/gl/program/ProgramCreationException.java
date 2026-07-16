package com.bdmajora.impetus.iris.gl.program;

/**
 * Thrown when a GL program fails to link or validate. Like {@link com.bdmajora.impetus.iris.gl.shader.ShaderCompileException}
 * this is caught by the pipeline so a malformed pack disables shaders rather than crashing the game.
 */
public class ProgramCreationException extends RuntimeException {
    public ProgramCreationException(String message) {
        super(message);
    }
}
