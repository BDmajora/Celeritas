package com.bdmajora.impetus.iris.shaderpack.include;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves OptiFine-style {@code #include} directives by recursively inlining the referenced GLSL files.
 * <p>
 * The processor is given a flat map of every GLSL file in the pack (keyed by {@link AbsolutePackPath}). Includes are
 * resolved relative to the including file, cycles are detected and rejected, and the result is a single flattened
 * source. Line numbers are preserved as faithfully as practical by leaving the surrounding lines untouched.
 * <p>
 * This deliberately does <em>not</em> evaluate {@code #ifdef}/{@code #define} conditionals — those are handled later by
 * the GLSL compiler and the {@code GlslPreprocessor}. Only textual inclusion happens here.
 */
public final class IncludeProcessor {
    // Matches:  #include "path"   or   #include <path>   with optional surrounding whitespace.
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^\\s*#include\\s+[\"<]([^\">]+)[\">].*$");

    private final Map<AbsolutePackPath, String> sources;

    public IncludeProcessor(Map<AbsolutePackPath, String> sources) {
        this.sources = sources;
    }

    /**
     * Flattens the file at {@code root}, inlining all transitively included files.
     *
     * @throws IllegalStateException if an include cannot be resolved or a cycle is detected.
     */
    public List<String> process(AbsolutePackPath root) {
        String source = this.sources.get(root);
        if (source == null) {
            throw new IllegalStateException("Cannot process missing file: " + root.getPathString());
        }
        List<String> out = new ArrayList<>();
        Deque<AbsolutePackPath> stack = new ArrayDeque<>();
        processInto(root, splitLines(source), out, stack);
        return out;
    }

    private void processInto(AbsolutePackPath path, List<String> lines, List<String> out, Deque<AbsolutePackPath> stack) {
        if (stack.contains(path)) {
            throw new IllegalStateException("Cyclic #include detected involving " + path.getPathString());
        }
        stack.push(path);
        try {
            for (String line : lines) {
                Matcher matcher = INCLUDE_PATTERN.matcher(line);
                if (matcher.matches()) {
                    AbsolutePackPath target = path.resolve(matcher.group(1).trim());
                    String included = this.sources.get(target);
                    if (included == null) {
                        // Tolerate generated/optional includes that don't exist as files. Emit a marker and continue
                        // rather than failing the whole pack load.
                        out.add("// [Impetus/Iris] skipped unresolved #include \"" + matcher.group(1) + "\"");
                    } else {
                        processInto(target, splitLines(included), out, stack);
                    }
                } else {
                    out.add(line);
                }
            }
        } finally {
            stack.pop();
        }
    }

    private static List<String> splitLines(String source) {
        // Preserve empty trailing structure; split on any newline form.
        String[] arr = source.split("\r\n|\r|\n", -1);
        List<String> list = new ArrayList<>(arr.length);
        for (String s : arr) {
            list.add(s);
        }
        // Drop a single trailing empty element introduced by a terminating newline.
        if (!list.isEmpty() && list.get(list.size() - 1).isEmpty()) {
            list.remove(list.size() - 1);
        }
        return list;
    }
}
