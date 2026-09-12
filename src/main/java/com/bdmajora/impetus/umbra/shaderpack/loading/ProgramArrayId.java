package com.bdmajora.impetus.umbra.shaderpack.loading;

// The numbered shader program families: begin, prepare, shadowcomp, deferred and composite
// Each is <base> then <base>1..N, where index 0 carries no numeric suffix — `composite` IS composite0
// The cap is 100 per family, matching Iris. OptiFine on 1.12.2 stopped at 16, but packs written against Iris
// routinely go past it: Photon ships composite16 (FXAA) and composite17 (the AO history copy), both of which
// silently vanished under the old cap and took their effects with them
// Declaration order here is the order the families run in a frame
public enum ProgramArrayId {
    // Compute-only and run ONCE at pack load rather than per frame — Photon's LPV initialisation is the example
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

    // composite, deferred or final
    public String getBaseName() {
        return this.baseName;
    }

    // How many numbered passes the family allows
    public int getNumPrograms() {
        return this.numPrograms;
    }

    // Turns a 0-based index into the source file base name: index 0 gives `composite`, index 3 gives `composite3`
    // The suffix-less index 0 is an OptiFine convention, not an off-by-one
    public String getSourceName(int index) {
        if (index < 0 || index >= this.numPrograms) {
            throw new IndexOutOfBoundsException("Program index " + index + " out of range for " + this.baseName);
        }
        return index == 0 ? this.baseName : this.baseName + index;
    }
}
