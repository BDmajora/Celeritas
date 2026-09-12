package com.bdmajora.impetus.umbra.shaderpack.option;

import com.bdmajora.impetus.umbra.shaderpack.include.AbsolutePackPath;

// Where one declaration of an option lives: a file within the pack, plus the line index inside it
// Options are merged across declaration sites, so a merged option keeps a set of these — that set is what lets the
// writer patch every copy of a `#define` when the user changes its value, rather than only the first one found
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

    // Zero-based INDEX, not a one-based line number: it is used to address the backing line list directly, so
    // off-by-one here rewrites the wrong line of the pack
    public int getLineIndex() {
        return lineIndex;
    }
}
