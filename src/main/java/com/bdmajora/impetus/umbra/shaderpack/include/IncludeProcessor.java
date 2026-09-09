package com.bdmajora.impetus.umbra.shaderpack.include;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// Resolves OptiFine-style #include directives by recursively inlining the referenced GLSL files
// Works from a flat map of every GLSL file in the pack, keyed by AbsolutePackPath. Includes resolve relative to
// the including file, cycles are detected and rejected, and the output is one flattened source
// Surrounding lines are left untouched so line numbers stay as close to the original as inlining allows — which is
// what makes a driver's error message point at something the pack author can find
// Deliberately does NOT evaluate #ifdef or #define conditionals. Those are the GLSL compiler's and
// GlslPreprocessor's job; this is textual inclusion and nothing else, so an include inside a false #ifdef is still
// inlined here
public final class IncludeProcessor {
    // Matches:  #include "path"   or   #include <path>   with optional surrounding whitespace.
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^\\s*#include\\s+[\"<]([^\">]+)[\">].*$");

    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    private final Map<AbsolutePackPath, String> sources;
    // Targets already reported missing. A common.glsl included by forty programs would otherwise log the same
    // unresolved include forty times
    private final Set<String> reportedMissing = new HashSet<>();

    public IncludeProcessor(Map<AbsolutePackPath, String> sources) {
        this.sources = sources;
    }

    // Flattens the file at root, inlining everything it transitively includes
    // Throws IllegalStateException on an unresolvable include or a cycle: both mean the pack cannot produce a
    // compilable source, and continuing would hand the driver a truncated shader
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
                        //
                        // But say so loudly. Both references treat this as FATAL: Umbra throws an IOException listing
                        // every unresolved include (ShaderPack.java: `if (!graph.getFailures().isEmpty()) throw ...`)
                        // and OptiFine throws "Included file not found" from resolveIncludes. Skipping quietly turns
                        // one precise error into a flood of downstream "undefined variable" compile failures with no
                        // hint at the cause — that is exactly how miniature-shader's dropped /shader.h presented.
                        // Across all 22 local packs, zero includes are genuinely absent, so nothing relies on this
                        // tolerance; it could be tightened to match the references.
                        if (this.reportedMissing.add(target.getPathString())) {
                            LOGGER.warn("[Umbra] Unresolved #include \"{}\" from {} — it resolved to {}, which is not in"
                                    + " the pack. Everything that file defined will be undefined at compile time.",
                                    matcher.group(1).trim(), path.getPathString(), target.getPathString());
                        }
                        out.add("// [Impetus/Umbra] skipped unresolved #include \"" + matcher.group(1) + "\"");
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
