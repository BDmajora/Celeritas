package com.bdmajora.impetus.iris.shaderpack.loading;

/**
 * The numbered shader program families: {@code deferred}/{@code deferred1..N}, {@code composite}/{@code composite1..N}
 * and {@code shadowcomp}/{@code shadowcomp1..N}. OptiFine on 1.12.2 supports up to 16 entries each (index 0 has no
 * numeric suffix, e.g. {@code composite} == {@code composite0}).
 */
public enum ProgramArrayId {
    ShadowComposite("shadowcomp", 16),
    Deferred("deferred", 16),
    Composite("composite", 16);

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
