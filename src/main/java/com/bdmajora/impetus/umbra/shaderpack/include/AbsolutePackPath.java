package com.bdmajora.impetus.umbra.shaderpack.include;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// An absolute, normalised path inside a shader pack's shaders/ directory, always starting with /
// OptiFine #include directives come in two forms: relative, resolved against the including file's directory, and
// absolute, where a leading / resolves from the pack root
// Normalising both into one canonical form — with . and .. segments collapsed — is what lets the include graph use
// these as map keys, so two paths that name the same file compare equal and a cycle is detectable
public final class AbsolutePackPath {
    private final String path;

    private AbsolutePackPath(String path) {
        this.path = path;
    }

    // Builds one from a string that is already absolute; the leading / is required, not optional
    public static AbsolutePackPath fromAbsolutePath(String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Not an absolute path: " + path);
        }
        return new AbsolutePackPath(normalize(path));
    }

    // Resolves an #include target against this path's PARENT directory, not against this path itself — a file
    // including "common.glsl" means the one beside it, not one beneath it
    // A leading / on the target ignores all of that and resolves from the pack root instead
    public AbsolutePackPath resolve(String target) {
        if (target.startsWith("/")) {
            return fromAbsolutePath(target);
        }
        // Resolve relative to the directory containing this file.
        int lastSlash = this.path.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "/" : this.path.substring(0, lastSlash + 1);
        return fromAbsolutePath(parent + target);
    }

    // Normalised, leading slash, forward slashes
    public String getPathString() {
        return this.path;
    }

    // Resolves . and .., collapses separators
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

    // By normalised string
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

    // By normalised string
    @Override
    public int hashCode() {
        return Objects.hashCode(this.path);
    }

    // The path string
    @Override
    public String toString() {
        return "AbsolutePackPath[" + this.path + "]";
    }
}
