package org.taumc.celeritas.iris.shaderpack;

import java.util.Optional;

/**
 * The flattened GLSL source for a single shader program: a mandatory vertex + fragment pair, with optional geometry,
 * tessellation control and tessellation evaluation stages.
 * <p>
 * "Flattened" means {@code #include} directives have already been resolved by the
 * {@link org.taumc.celeritas.iris.shaderpack.include.IncludeProcessor}. The strings here are still pre-compilation —
 * {@code #version} normalization and {@code #define} injection happen at GL-program build time.
 */
public final class ProgramSource {
    private final String name;
    private final String vertexSource;
    private final String geometrySource;
    private final String tessControlSource;
    private final String tessEvalSource;
    private final String fragmentSource;

    public ProgramSource(String name,
                         String vertexSource,
                         String geometrySource,
                         String tessControlSource,
                         String tessEvalSource,
                         String fragmentSource) {
        this.name = name;
        this.vertexSource = vertexSource;
        this.geometrySource = geometrySource;
        this.tessControlSource = tessControlSource;
        this.tessEvalSource = tessEvalSource;
        this.fragmentSource = fragmentSource;
    }

    public String getName() {
        return this.name;
    }

    public Optional<String> getVertexSource() {
        return Optional.ofNullable(this.vertexSource);
    }

    public Optional<String> getGeometrySource() {
        return Optional.ofNullable(this.geometrySource);
    }

    public Optional<String> getTessControlSource() {
        return Optional.ofNullable(this.tessControlSource);
    }

    public Optional<String> getTessEvalSource() {
        return Optional.ofNullable(this.tessEvalSource);
    }

    public Optional<String> getFragmentSource() {
        return Optional.ofNullable(this.fragmentSource);
    }

    /**
     * A program is only usable if it has at least a vertex and a fragment stage. OptiFine treats a program with only
     * one of the two as malformed, falling back to the parent program.
     */
    public boolean isValid() {
        return this.vertexSource != null && this.fragmentSource != null;
    }
}
