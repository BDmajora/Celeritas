package com.bdmajora.impetus.umbra.gl.program;

// Thrown when a program fails to link or validate; unchecked and caught like ShaderCompileException, so a malformed pack drops to vanilla instead of crashing
public class ProgramCreationException extends RuntimeException {
    public ProgramCreationException(String message) {
        super(message);
    }
}
