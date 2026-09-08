package com.bdmajora.impetus.umbra.shaderpack.include;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An absolute, normalized path within a shader pack's {@code shaders/} directory, always starting with {@code /}.
 * <p>
 * OptiFine {@code #include} directives may be relative (resolved against the including file's directory) or absolute
 * (a leading {@code /} resolves from the pack root). This class encapsulates that normalization, collapsing
 * {@code .} and {@code ..} segments, so include graphs can use it as a stable map key.
 */
public final class AbsolutePackPath {
    private final String path;

    private AbsolutePackPath(String path) {
        this.path = path;
    }

    /**
     * Builds an absolute path from an already-absolute string (must start with {@code /}).
     */
    public static AbsolutePackPath fromAbsolutePath(String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Not an absolute path: " + path);
        }
        return new AbsolutePackPath(normalize(path));
    }

    /**
     * Resolves an {@code #include} target against this path's parent directory, honoring OptiFine's relative vs
     * absolute (leading {@code /}) rules.
     */
    public AbsolutePackPath resolve(String target) {
        if (target.startsWith("/")) {
            return fromAbsolutePath(target);
        }
        // Resolve relative to the directory containing this file.
        int lastSlash = this.path.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "/" : this.path.substring(0, lastSlash + 1);
        return fromAbsolutePath(parent + target);
    }

    public String getPathString() {
        return this.path;
    }

    private static String normalize(String raw) {
        String[] segments = raw.split("/");
        List<String> out = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            out.add(segment);
        }
        return "/" + String.join("/", out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AbsolutePackPath)) {
            return false;
        }
        return this.path.equals(((AbsolutePackPath) o).path);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.path);
    }

    @Override
    public String toString() {
        return "AbsolutePackPath[" + this.path + "]";
    }
}
