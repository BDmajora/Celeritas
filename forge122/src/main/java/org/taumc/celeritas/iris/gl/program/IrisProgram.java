package org.taumc.celeritas.iris.gl.program;

/**
 * A fully compiled shader-pack program: the linked {@link GlProgram} plus the color attachments it declares it writes
 * to (its {@code DRAWBUFFERS}/{@code RENDERTARGETS} set). Uniform and sampler binding are attached separately by the
 * pipeline once the render targets exist.
 */
public class IrisProgram {
    private final GlProgram program;
    private final int[] drawBuffers;

    public IrisProgram(GlProgram program, int[] drawBuffers) {
        this.program = program;
        this.drawBuffers = drawBuffers;
    }

    public GlProgram getProgram() {
        return this.program;
    }

    public int[] getDrawBuffers() {
        return this.drawBuffers;
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
