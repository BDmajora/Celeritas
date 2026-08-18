package com.bdmajora.impetus.iris.shaderpack.preprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Light-touch GLSL source transformation applied after {@code #include} flattening and before GL compilation.
 * <p>
 * Responsibilities handled here (Phase 1):
 * <ul>
 *     <li>Locating the {@code #version} directive (which GLSL requires to be the first non-comment, non-blank line)
 *         and exposing the declared version.</li>
 *     <li>Injecting {@code #define} macros (shader pack options + pipeline feature flags) immediately after the
 *         {@code #version} line, where they are legal.</li>
 *     <li>Injecting a default {@code #version} when a pack omits one (1.12.2 packs commonly target {@code 120}).</li>
 * </ul>
 * <p>
 * Legacy fixed-function built-in substitution ({@code gl_MultiTexCoord0}, {@code gl_Color}, {@code gl_Normal}, ...)
 * into explicit vertex attributes is intentionally <em>not</em> performed here: it depends on the concrete attribute
 * bindings established by the GL program layer and is implemented in a later phase. {@link #replaceLegacyBuiltins} is
 * provided as the documented seam for that work.
 */
public final class GlslPreprocessor {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\s*#version\\s+(\\d+)(?:\\s+(\\w+))?.*$");

    /** Default GLSL version assumed for 1.12.2-era packs that omit a {@code #version} directive. */
    public static final int DEFAULT_VERSION = 120;

    private GlslPreprocessor() {
    }

    /**
     * @return the GLSL version number declared by the first {@code #version} directive, or {@code -1} if none.
     */
    public static int detectVersion(List<String> lines) {
        for (String line : lines) {
            Matcher m = VERSION_PATTERN.matcher(line);
            if (m.matches()) {
                return Integer.parseInt(m.group(1));
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            // First meaningful line wasn't #version; GLSL will assume 110 but packs rely on 120 built-ins.
            return -1;
        }
        return -1;
    }

    /**
     * Inserts the given macro definitions immediately after the {@code #version} line (inserting a default
     * {@code #version} first if the source has none), returning the rewritten source lines.
     *
     * @param defines ordered macro name -> value; an empty value yields a bare {@code #define NAME}.
     */
    public static List<String> injectDefines(List<String> lines, Map<String, String> defines) {
        List<String> out = new ArrayList<>(lines.size() + defines.size() + 1);

        int versionIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (VERSION_PATTERN.matcher(lines.get(i)).matches()) {
                versionIndex = i;
                break;
            }
        }

        if (versionIndex < 0) {
            // No #version present: prepend a default one, then defines, then the original source.
            out.add("#version " + DEFAULT_VERSION);
            out.addAll(toDefineLines(defines));
            out.addAll(lines);
            return out;
        }

        out.add(lines.get(versionIndex));
        out.addAll(toDefineLines(defines));
        for (int i = 0; i < lines.size(); i++) {
            if (i == versionIndex || VERSION_PATTERN.matcher(lines.get(i)).matches()) {
                continue;
            }
            out.add(lines.get(i));
        }
        return out;
    }

    private static List<String> toDefineLines(Map<String, String> defines) {
        List<String> result = new ArrayList<>(defines.size());
        for (Map.Entry<String, String> e : defines.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                result.add("#define " + e.getKey());
            } else {
                result.add("#define " + e.getKey() + " " + e.getValue());
            }
        }
        return result;
    }

    /**
     * Drops the branches of {@code #if}/{@code #ifdef} conditionals the given macro set does not take, so a scanner
     * sees only the declarations the GPU will actually compile. <b>For directive extraction only</b> — the result is
     * not compilable source (every {@code #} line, {@code #version} and {@code #extension} included, is consumed).
     * <p>
     * Iris scans source that JCPP has already preprocessed ({@code ShaderPack}'s source provider feeds
     * {@code ProgramSet}, which runs {@code ConstDirectiveParser} over it), so a directive sitting in a dead branch is
     * simply not there. A raw-text regex instead takes the first textual match, which happily reads a value out of a
     * branch the pack disabled.
     * <p>
     * Resolve one stage at a time: define state and {@code #if} nesting must not leak from one file into the next, and
     * packs select which half of a shared body to compile with a {@code #define VERTEX_SHADER}/{@code FRAGMENT_SHADER}
     * at the top of each entry point. Note that resolving consumes the {@code #define} lines it evaluates, so
     * {@code #define}-form directives ({@code SHADOWRES}, {@code SHADOWFOV}) must still be read from the raw source.
     *
     * @return the resolved source, or {@code source} unchanged if the conditionals could not be evaluated
     */
    public static String resolveConditionals(String source, Map<String, String> defines) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        try {
            String resolved = PropertiesPreprocessor.preprocess(source, defines);
            // An unterminated #if can swallow the rest of the file; keep the raw source rather than scan nothing.
            return resolved.trim().isEmpty() ? source : resolved;
        } catch (RuntimeException e) {
            return source;
        }
    }

    /** A GLSL floating-point literal: {@code 1.0}, {@code .5}, {@code 0.}, {@code 1e-3}. */
    private static final Pattern FLOAT_LITERAL =
            Pattern.compile("(?<![A-Za-z0-9_.])(?:\\d+\\.\\d*|\\.\\d+|\\d+[eE][-+]?\\d+)");
    /** {@code defined X} / {@code defined(X)} — the one place an identifier must NOT be macro-expanded. */
    private static final Pattern DEFINED_OPERATOR =
            Pattern.compile("\\bdefined\\s*(?:\\(\\s*\\w+\\s*\\)|\\s+\\w+)");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern CONDITIONAL_DIRECTIVE =
            Pattern.compile("(?m)^([\\t ]*#[\\t ]*)(if|elif)\\b([^\\n]*)$");

    /**
     * Folds {@code #if}/{@code #elif} conditionals whose expression works out to a floating-point comparison into a
     * literal {@code 1}/{@code 0}, leaving every other directive for the driver.
     * <p>
     * The C (and therefore GLSL) preprocessor grammar is integer-only, so a driver rejects
     * {@code #if MOTION_BLUR > 0.0} outright — NVIDIA with {@code C0105: Syntax error in #if}, which took Clarity's
     * whole {@code composite.csh} down. Iris never hits this because it runs the entire source through JCPP before
     * the driver sees it, and JCPP accepts float literals by truncating them toward zero. Rather than take on a full
     * preprocessor, this resolves only the directives that cannot legally reach the driver, so everything else
     * (including {@code #ifdef} trees and line numbering, since each folded directive stays on its own line) is
     * untouched.
     * <p>
     * Macro state is tracked the way the driver would see it, one line at a time, so a macro defined differently in
     * two branches still resolves to the definition in force at the directive being folded. A conditional this
     * evaluator cannot parse counts as taken for that bookkeeping — the same conservative fallback
     * {@link #resolveConditionals} uses — and is never itself folded. Backslash-continued conditionals therefore fall
     * through untouched as well, which is correct: a directive spread over several lines cannot be replaced by a
     * single one without shifting every line number after it.
     */
    public static String foldFloatConditionals(String source, Map<String, String> defines) {
        if (source == null || source.indexOf('#') < 0) {
            return source;
        }
        Map<String, String> active = new LinkedHashMap<>(defines);
        StringBuilder out = new StringBuilder(source.length());
        // Nesting levels, each [0] = this branch active, [1] = some branch already taken.
        java.util.Deque<boolean[]> stack = new java.util.ArrayDeque<>();
        String[] lines = source.split("\n", -1);
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // The real preprocessor strips comments before it looks for directives, so a `#if` commented out with
            // /* */ is not one. Packs do exactly this (Clarity parks a dead #ifdef/#endif pair in a comment block);
            // interpreting those would desync the macro bookkeeping below from what the driver sees.
            boolean commented = inBlockComment;
            inBlockComment = advanceBlockComment(line, inBlockComment);
            out.append(commented ? line : foldDirective(line, active, stack));
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** {@return whether the line ends inside a block comment, given whether it started inside one} */
    private static boolean advanceBlockComment(String line, boolean inBlockComment) {
        for (int i = 0; i < line.length() - 1; i++) {
            if (inBlockComment) {
                if (line.charAt(i) == '*' && line.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
            } else if (line.charAt(i) == '/' && line.charAt(i + 1) == '/') {
                return false;
            } else if (line.charAt(i) == '/' && line.charAt(i + 1) == '*') {
                inBlockComment = true;
                i++;
            }
        }
        return inBlockComment;
    }

    private static String foldDirective(String line, Map<String, String> defines, java.util.Deque<boolean[]> stack) {
        Matcher conditional = CONDITIONAL_DIRECTIVE.matcher(line);
        if (conditional.matches()) {
            String expression = stripComment(conditional.group(3));
            boolean[] frame = "elif".equals(conditional.group(2)) ? stack.poll() : null;
            boolean alreadyTaken = frame != null && frame[1];
            Boolean value = PropertiesPreprocessor.tryEvaluateBooleanExpression(expression, defines).orElse(null);
            boolean malformed = value == null && !isContinued(expression) && isSyntacticallyInvalid(expression);
            boolean taken = !alreadyTaken && allActive(stack) && !malformed && (value == null || value);
            stack.push(new boolean[]{taken, alreadyTaken || taken});
            if (malformed || (value != null && expandsToFloat(expression, defines, 0))) {
                return conditional.group(1) + conditional.group(2) + " " + (!malformed && value ? "1" : "0");
            }
            return line;
        }

        String trimmed = line.trim();
        if (!trimmed.startsWith("#")) {
            return line;
        }
        // Comments come off first, as the real preprocessor does: `#endif // label` and `#else /* label */` are
        // idiomatic in packs, and a trailing label that stopped `#else` from being recognized would leave the
        // branch stack — and every define recorded after it — out of step with the driver.
        String directive = stripComment(trimmed.substring(1)).trim();
        if (directive.startsWith("ifdef ") || directive.startsWith("ifndef ")) {
            boolean defined = defines.containsKey(directive.substring(directive.indexOf(' ') + 1).trim());
            boolean taken = allActive(stack) && (directive.startsWith("ifdef ") == defined);
            stack.push(new boolean[]{taken, taken});
        } else if (directive.equals("else")) {
            boolean[] frame = stack.poll();
            boolean alreadyTaken = frame != null && frame[1];
            stack.push(new boolean[]{!alreadyTaken && allActive(stack), true});
        } else if (directive.equals("endif")) {
            stack.poll();
        } else if (directive.startsWith("define ") && allActive(stack)) {
            String body = directive.substring("define ".length()).trim();
            int space = body.indexOf(' ');
            int paren = body.indexOf('(');
            if (paren >= 0 && (space < 0 || paren < space)) {
                defines.put(body.substring(0, paren), "");
            } else if (space < 0) {
                defines.put(body, "");
            } else {
                defines.put(body.substring(0, space), body.substring(space + 1).trim());
            }
        } else if (directive.startsWith("undef ") && allActive(stack)) {
            defines.remove(directive.substring("undef ".length()).trim());
        }
        return line;
    }

    /**
     * Whether {@code expression} is something no preprocessor could accept, so folding it to {@code 0} is strictly
     * better than forwarding it to the driver.
     * <p>
     * This is deliberately narrow. "Our evaluator could not parse it" is <em>not</em> grounds to drop a branch — the
     * driver's preprocessor is the more capable one, and killing a live branch on a parser shortfall would be a far
     * worse failure than the one being fixed. Unbalanced parentheses are the one signal that needs no judgement: no
     * valid {@code #if} expression has them, so the driver is guaranteed to reject the directive as well, and
     * rejecting it costs the entire program rather than one branch.
     * <p>
     * Real case: Pastel v1.200 ships {@code #if (in(biome, BIOME_SOUL_SAND_VALLEY)} in
     * {@code lib/atmospherics/fog.glsl} — shaders.properties custom-uniform syntax pasted into GLSL, missing a
     * paren, and sitting in the {@code #else} of an {@code #ifdef NETHER}, so the overworld build reaches it. NVIDIA
     * answers {@code C0105: Syntax error in #if} and Pastel's whole {@code deferred1} pass — its lighting and fog —
     * is dropped. The branch cannot have been intended to compile, so taking it as false is what the pack means.
     */
    /**
     * Whether this directive is only the first line of a backslash-continued one, so the rest of its expression — and
     * very likely the parentheses that balance it — is on the following lines.
     * <p>
     * This guard is what keeps {@link #isSyntacticallyInvalid} honest, and it is not theoretical: Photon has three of
     * these ({@code gbuffers_all_solid.fsh:308}, {@code gbuffers_all_translucent.vsh:109},
     * {@code include/vertex/utility.glsl:21}, e.g. {@code #elif ( \}). Judged one line at a time every one of them
     * looks unbalanced, and folding them to {@code 0} would silently delete live branches from Photon's main gbuffer
     * programs. A sweep of all 22 installed packs finds exactly these three continuations and one genuinely broken
     * directive (Pastel's), which is the split this pair of checks has to reproduce.
     */
    private static boolean isContinued(String expression) {
        String trimmed = expression.trim();
        return trimmed.endsWith("\\");
    }

    private static boolean isSyntacticallyInvalid(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth < 0) {
                return true;
            }
        }
        return depth != 0;
    }

    private static boolean allActive(java.util.Deque<boolean[]> stack) {
        for (boolean[] frame : stack) {
            if (!frame[0]) {
                return false;
            }
        }
        return true;
    }

    /** Truncates at the first comment opener of either kind; a directive never carries meaning past one. */
    private static String stripComment(String text) {
        int line = text.indexOf("//");
        int block = text.indexOf("/*");
        int comment = line < 0 ? block : (block < 0 ? line : Math.min(line, block));
        return comment < 0 ? text : text.substring(0, comment);
    }

    /** Whether the expression contains a float literal once its macros are substituted, as the driver would see it. */
    private static boolean expandsToFloat(String expression, Map<String, String> defines, int depth) {
        if (FLOAT_LITERAL.matcher(expression).find()) {
            return true;
        }
        if (depth >= 8) {
            return false;
        }
        Matcher identifiers = IDENTIFIER.matcher(DEFINED_OPERATOR.matcher(expression).replaceAll(""));
        while (identifiers.find()) {
            String value = defines.get(identifiers.group());
            if (value != null && !value.isEmpty() && expandsToFloat(value, defines, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Seam for later phases: rewrite legacy fixed-function built-ins to explicit {@code in}/attribute names.
     * Because Impetus renders chunks through VAOs, the fixed-function attribute slots are never populated, so a
     * GLSL-150+ translation needs to map e.g. {@code gl_MultiTexCoord0 -> vec4(mc_midTexCoord, 0.0, 1.0)}.
     * Not implemented in Phase 1; returns the input unchanged.
     */
    public static List<String> replaceLegacyBuiltins(List<String> lines, Map<String, String> attributeBindings) {
        // Intentionally a no-op for Phase 1. Kept as an explicit extension point.
        return lines;
    }

    /** Convenience: a stable, insertion-ordered map suitable for {@link #injectDefines}. */
    public static Map<String, String> newDefineMap() {
        return new LinkedHashMap<>();
    }
}
