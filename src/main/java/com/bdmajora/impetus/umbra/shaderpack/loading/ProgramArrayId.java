package com.bdmajora.impetus.umbra.shaderpack.loading;

// The numbered program families (begin, prepare, shadowcomp, deferred, composite): <base> then <base>1..N, index 0 unsuffixed; capped at 100 like Iris rather than OptiFine's 16, since Photon ships composite16/17 and lost FXAA and its AO history under the old cap. Declaration order is frame order
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

    // Turns a 0-based index into the source base name: 0 gives `composite`, 3 gives `composite3`; the suffix-less 0 is an OptiFine convention
    public String getSourceName(int index) {
        if (index < 0 || index >= this.numPrograms) {
            throw new IndexOutOfBoundsException("Program index " + index + " out of range for " + this.baseName);
        }
        return index == 0 ? this.baseName : this.baseName + index;
    }
}
