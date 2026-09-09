package com.bdmajora.impetus.umbra.shaderpack;

import java.util.Optional;

// The flattened GLSL source for one shader program: a mandatory vertex + fragment pair, plus optional geometry and
// tessellation control/evaluation stages
// "Flattened" means IncludeProcessor has already resolved every #include, so each string is one self-contained
// source rather than a file with references out
// Still pre-compilation though: #version normalisation and #define injection both happen later, at GL-program build
// time, because they depend on the driver and the resolved option values
public final class ProgramSource {
    // Iris's compute-variant limit: the unsuffixed .csh plus _a through _z, so 1 + 26
    public static final int MAX_COMPUTE_VARIANTS = 27;

    private final String name;
    private final String vertexSource;
    private final String geometrySource;
    private final String tessControlSource;
    private final String tessEvalSource;
    private final String fragmentSource;
    // The program's compute stages. Index 0 is <name>.csh; 1..26 are <name>_a.csh through <name>_z.csh, which is
    // an Iris extension — Photon's deferred4_a.csh is the one that generates the skylight SH
    // Empty when the program declares no compute stage at all, and entries inside it may still be null, since a
    // pack can ship _a and _c without _b
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

    // The name a compute variant is compiled and logged under: variant 0 is the unsuffixed name, 1..26 append
    // _a through _z
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

    // The unsuffixed compute stage alone, which is what the shadowcomp and composite compute passes run
    public Optional<String> getComputeSource() {
        return Optional.ofNullable(this.computeSources.length == 0 ? null : this.computeSources[0]);
    }

    // Every compute stage on this program, indexed by variant — 0 unsuffixed, 1..26 for _a through _z
    // May be empty and may contain nulls, so callers index defensively rather than iterating a dense list
    public String[] getComputeSources() {
        return this.computeSources.clone();
    }

    // True when at least one compute stage exists, suffixed or not
    public boolean hasComputeSource() {
        for (String source : this.computeSources) {
            if (source != null) {
                return true;
            }
        }
        return false;
    }

    // A program needs BOTH a vertex and a fragment stage to be usable
    // OptiFine treats one without the other as malformed and falls back to the parent program rather than trying to
    // compile half a pipeline, which is what ProgramSet.get reproduces
    public boolean isValid() {
        // A compute-only program (shadowcomp.csh, deferred4_a.csh) is valid without vertex/fragment stages.
        return (this.vertexSource != null && this.fragmentSource != null) || hasComputeSource();
    }

    // True when the vertex+fragment pair needed to raster anything is present — distinct from isValid only in
    // intent: this asks "can it draw", where a compute-only program legitimately cannot
    public boolean hasRasterStages() {
        return this.vertexSource != null && this.fragmentSource != null;
    }
}
