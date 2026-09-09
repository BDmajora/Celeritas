package com.bdmajora.impetus.umbra.gl.program;

// A fully compiled shader-pack program: the linked GlProgram plus the colour attachments it declared it writes,
// i.e. its DRAWBUFFERS / RENDERTARGETS set
// Uniforms and samplers are NOT here — the pipeline attaches those separately, once the render targets exist and
// their texture ids are known
public class UmbraProgram {
    private final GlProgram program;
    private final int[] drawBuffers;

    // Both branches clone: the array is handed out again by getDrawBuffers, and DrawBuffers.DEFAULT is shared,
    // so keeping either reference would let a caller mutate the default for every program that followed
    public UmbraProgram(GlProgram program, int[] drawBuffers) {
        this.program = program;
        this.drawBuffers = drawBuffers == null ? DrawBuffers.DEFAULT.clone() : drawBuffers.clone();
    }

    public GlProgram getProgram() {
        return this.program;
    }

    // Cloned for the same reason; callers pass this straight to glDrawBuffers and some sort or filter it first
    public int[] getDrawBuffers() {
        return this.drawBuffers.clone();
    }

    public void bind() {
        this.program.bind();
    }

    public void unbind() {
        this.program.unbind();
    }

    public void destroy() {
        this.program.destroy();
    }
}
