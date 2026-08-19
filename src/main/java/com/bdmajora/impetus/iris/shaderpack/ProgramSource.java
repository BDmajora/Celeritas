package com.bdmajora.impetus.iris.shaderpack;

import java.util.Optional;

/**
 * The flattened GLSL source for a single shader program: a mandatory vertex + fragment pair, with optional geometry,
 * tessellation control and tessellation evaluation stages.
 * <p>
 * "Flattened" means {@code #include} directives have already been resolved by the
 * {@link com.bdmajora.impetus.iris.shaderpack.include.IncludeProcessor}. The strings here are still pre-compilation —
 * {@code #version} normalization and {@code #define} injection happen at GL-program build time.
 */
public final class ProgramSource {
    /** Iris's compute-variant limit: the unsuffixed {@code .csh} plus {@code _a} .. {@code _z}. */
    public static final int MAX_COMPUTE_VARIANTS = 27;

    private final String name;
    private final String vertexSource;
    private final String geometrySource;
    private final String tessControlSource;
    private final String tessEvalSource;
    private final String fragmentSource;
    /**
     * The program's compute stages: index 0 is {@code <name>.csh}, index 1..26 are {@code <name>_a.csh} ..
     * {@code <name>_z.csh} (an Iris extension — Photon's {@code deferred4_a.csh} generates the skylight SH). Empty
     * when the program declares no compute stage at all; entries inside it may still be null.
     */
    private final String[] computeSources;

    public ProgramSource(String name,
                         String vertexSource,
                         String geometrySource,
                         String tessControlSource,
                         String tessEvalSource,
                         String fragmentSource) {
        this(name, vertexSource, geometrySource, tessControlSource, tessEvalSource, fragmentSource, (String[]) null);
    }

    public ProgramSource(String name,
                         String vertexSource,
                         String geometrySource,
                         String tessControlSource,
                         String tessEvalSource,
                         String fragmentSource,
                         String computeSource) {
        this(name, vertexSource, geometrySource, tessControlSource, tessEvalSource, fragmentSource,
                computeSource == null ? null : new String[]{computeSource});
    }

    public ProgramSource(String name,
                         String vertexSource,
                         String geometrySource,
                         String tessControlSource,
                         String tessEvalSource,
                         String fragmentSource,
                         String[] computeSources) {
        this.name = name;
        this.vertexSource = vertexSource;
        this.geometrySource = geometrySource;
        this.tessControlSource = tessControlSource;
        this.tessEvalSource = tessEvalSource;
        this.fragmentSource = fragmentSource;
        this.computeSources = computeSources == null ? new String[0] : computeSources.clone();
    }

    /**
     * @param variant 0 for the unsuffixed {@code .csh}, 1..26 for {@code _a} .. {@code _z}
     * @return the source name the GL program should be logged/compiled under.
     */
    public static String computeVariantName(String programName, int variant) {
        return variant == 0 ? programName : programName + "_" + (char) ('a' + variant - 1);
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

    /** The unsuffixed compute stage ({@code <name>.csh}), used by shadowcomp/composite compute passes. */
    public Optional<String> getComputeSource() {
        return Optional.ofNullable(this.computeSources.length == 0 ? null : this.computeSources[0]);
    }

    /**
     * Every compute stage attached to this program, indexed by variant (0 = unsuffixed, 1..26 = {@code _a}..{@code _z}).
     * The returned array may be empty and may contain nulls; use {@link #computeVariantName} for the variant's name.
     */
    public String[] getComputeSources() {
        return this.computeSources.clone();
    }

    /** @return true if this program declares at least one compute stage (unsuffixed or letter-suffixed). */
    public boolean hasComputeSource() {
        for (String source : this.computeSources) {
            if (source != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * A program is only usable if it has at least a vertex and a fragment stage. OptiFine treats a program with only
     * one of the two as malformed, falling back to the parent program.
     */
    public boolean isValid() {
        // A compute-only program (shadowcomp.csh, deferred4_a.csh) is valid without vertex/fragment stages.
        return (this.vertexSource != null && this.fragmentSource != null) || hasComputeSource();
    }

    /** @return true if this program has the vertex+fragment pair needed to render a full-screen/geometry pass. */
    public boolean hasRasterStages() {
        return this.vertexSource != null && this.fragmentSource != null;
    }
}
