package com.bdmajora.impetus.umbra.gl.program;

// A fully compiled pack program: the linked GlProgram plus its declared DRAWBUFFERS/RENDERTARGETS set; uniforms and samplers are attached separately once render targets exist
public class UmbraProgram {
    private final GlProgram program;
    private final int[] drawBuffers;

    // Both branches clone: the array is handed out by getDrawBuffers and DrawBuffers.DEFAULT is shared, so keeping either reference would let a caller mutate the default for every later program
    public UmbraProgram(GlProgram program, int[] drawBuffers) {
        this.program = program;
        this.drawBuffers = drawBuffers == null ? DrawBuffers.DEFAULT.clone() : drawBuffers.clone();
    }

    // The linked program
    public GlProgram getProgram() {
        return this.program;
    }

    // Cloned for the same reason; callers pass this straight to glDrawBuffers and some sort or filter it first
    public int[] getDrawBuffers() {
        return this.drawBuffers.clone();
    }

    // Binds the program and applies its draw buffers
    public void bind() {
        this.program.bind();
    }

    // glUseProgram(0)
    public void unbind() {
        this.program.unbind();
    }

    // Frees the program
    public void destroy() {
        this.program.destroy();
    }
}
