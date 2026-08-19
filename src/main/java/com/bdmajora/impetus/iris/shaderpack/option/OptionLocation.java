package com.bdmajora.impetus.iris.shaderpack.option;

import com.bdmajora.impetus.iris.shaderpack.include.AbsolutePackPath;

/**
 * Encapsulates a single location of an option (a file plus the line index within it). Ported from Iris.
 */
public class OptionLocation {
    private final AbsolutePackPath filePath;
    private final int lineIndex;

    public OptionLocation(AbsolutePackPath filePath, int lineIndex) {
        this.filePath = filePath;
        this.lineIndex = lineIndex;
    }

    public AbsolutePackPath getFilePath() {
        return filePath;
    }

    /**
     * Gets the index of the line this option is on. Note that this is the index - so the first line is 0, the second
     * is 1, etc.
     */
    public int getLineIndex() {
        return lineIndex;
    }
}
