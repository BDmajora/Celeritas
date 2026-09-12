package com.bdmajora.impetus.umbra.shaderpack.option;

import com.bdmajora.impetus.umbra.shaderpack.include.AbsolutePackPath;

// Where one declaration of an option lives (file plus line index); a merged option keeps a set of these so the writer patches every copy of a `#define`, not just the first
public class OptionLocation {
    private final AbsolutePackPath filePath;
    private final int lineIndex;

    public OptionLocation(AbsolutePackPath filePath, int lineIndex) {
        this.filePath = filePath;
        this.lineIndex = lineIndex;
    }

    // Which file
    public AbsolutePackPath getFilePath() {
        return filePath;
    }

    // Zero-based INDEX, not a one-based line number: it addresses the backing line list directly, so an off-by-one rewrites the wrong line
    public int getLineIndex() {
        return lineIndex;
    }
}
