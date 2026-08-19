package com.bdmajora.impetus.iris.shaderpack.loading;

/**
 * The numbered shader program families: {@code begin}, {@code prepare}, {@code shadowcomp}, {@code deferred} and
 * {@code composite}, each {@code <base>}/{@code <base>1..N} (index 0 has no numeric suffix, e.g. {@code composite} ==
 * {@code composite0}).
 * <p>
 * Iris allows 100 entries per family; OptiFine on 1.12.2 stopped at 16, but packs written against Iris routinely go
 * past that — Photon ships {@code composite16} (FXAA) and {@code composite17} (AO history copy), which silently
 * vanished under the old cap. Declaration order here is the order the families run in a frame.
 */
public enum ProgramArrayId {
    /** Compute-only, run once when the pack loads (Iris {@code setup}, e.g. Photon's LPV initialization). */
    Setup("setup", 100),
    Begin("begin", 100),
    ShadowComposite("shadowcomp", 100),
    Prepare("prepare", 100),
    Deferred("deferred", 100),
    Composite("composite", 100);

    private final String baseName;
    private final int numPrograms;

    ProgramArrayId(String baseName, int numPrograms) {
        this.baseName = baseName;
        this.numPrograms = numPrograms;
    }

    public String getBaseName() {
        return this.baseName;
    }

    public int getNumPrograms() {
        return this.numPrograms;
    }

    /**
     * @param index 0-based program index
     * @return the source file base name, e.g. {@code composite} for index 0, {@code composite3} for index 3.
     */
    public String getSourceName(int index) {
        if (index < 0 || index >= this.numPrograms) {
            throw new IndexOutOfBoundsException("Program index " + index + " out of range for " + this.baseName);
        }
        return index == 0 ? this.baseName : this.baseName + index;
    }
}
