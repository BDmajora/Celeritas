package com.bdmajora.impetus.umbra.gl.shader;

// Thrown when a GLSL stage fails to compile; carries the shader name and driver info log so the pipeline
// can log a useful diagnostic and fall back to vanilla rendering instead of crashing
public class ShaderCompileException extends RuntimeException {
    private final String filename;
    private final String error;

    public ShaderCompileException(String filename, String error) {
        super("Failed to compile " + filename + ": " + error);
        this.filename = filename;
        this.error = error;
    }

    // Which pack file failed
    public String getFilename() {
        return this.filename;
    }

    // The driver's info log
    public String getError() {
        return this.error;
    }
}
