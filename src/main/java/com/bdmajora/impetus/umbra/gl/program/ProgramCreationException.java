package com.bdmajora.impetus.umbra.gl.program;

// Thrown when a GL program fails to link or validate
// Unchecked, and caught by the pipeline the same way ShaderCompileException is, so a malformed pack disables
// shaders and drops back to vanilla rendering rather than taking the game down
public class ProgramCreationException extends RuntimeException {
    public ProgramCreationException(String message) {
        super(message);
    }
}
