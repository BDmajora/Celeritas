package com.bdmajora.impetus.umbra.terrain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves non-constant global-variable initializers into the generated {@code main()}.
 * <p>
 * GLSL requires global initializers to be constant expressions; legacy packs freely write things like
 * {@code float comp = 1.0 - near/far/far;} at global scope (legal-in-practice under {@code #version 120}, where
 * drivers evaluate them per invocation). Under {@code #version 330} NVIDIA accepts them silently but may evaluate
 * before uniforms are loaded — zeros/NaNs that silently corrupt whole passes (this exact class made terrain render
 * nothing until the prologue matrices were converted to defines). Hoisting the assignment into {@code main()}
 * reproduces the 120 semantics exactly.
 * <p>
 * Line-based with brace-depth tracking: only depth-0, single-declarator initializers of simple types are touched, and
 * only when the initializer references an identifier that is not a literal/type-constructor (moving a genuinely
 * constant initializer would also be safe — the whitelist just minimizes churn). The initializer expression may span
 * several lines: BSL's {@code weatherCol = mix( ... )} is written across nine lines, and if only the single-line
 * neighbours ({@code weatherRain}, {@code weatherWeight}) are hoisted while {@code weatherCol}'s multi-line initializer
 * is left at global scope, it evaluates against still-zero inputs → {@code vec4(0)} → {@code lightCol}/{@code ambientCol}
 * collapse to black under rain (terrain goes near-black in weather). So the collector accumulates continuation lines up
 * to the statement-terminating {@code ;} at paren/bracket/brace nesting 0.
 * <p>
 * Top-level preprocessor conditionals are mirrored into the hoisted assignment stream. Packs such as Sildur's put
 * non-constant globals inside {@code #ifdef}/{@code #if} option gates; moving the assignments outside those gates makes
 * the generated {@code main()} reference declarations that the GLSL preprocessor removed.
 */
public final class GlslGlobalInitHoister {
    /** Matches the START of a global initializer: indent, type, name, {@code =}, and the rest of the first line. */
    private static final Pattern GLOBAL_INIT_START = Pattern.compile(
            "^(\\s*)(float|int|bool|vec[234]|ivec[234]|mat[234])\\s+(\\w+)\\s*=\\s*(.*)$");
    private static final Pattern TRAILING_AFTER_TERMINATOR = Pattern.compile("\\s*(?://.*)?");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern CONSTANT_CALLEES = Pattern.compile(
            "vec[234]|ivec[234]|mat[234]|float|int|bool|true|false");

    /** The rewritten source (initializers stripped) plus the assignments to run at the top of {@code main()}. */
    public static final class Result {
        public final String body;
        public final String hoistedAssignments;

        Result(String body, String hoistedAssignments) {
            this.body = body;
            this.hoistedAssignments = hoistedAssignments;
        }
    }

    private GlslGlobalInitHoister() {
    }

    public static Result hoist(String source) {
        StringBuilder body = new StringBuilder(source.length());
        StringBuilder hoisted = new StringBuilder();
        int depth = 0;
        boolean hasHoistedAssignments = false;

        String[] lines = source.split("\n", -1);
        for (int idx = 0; idx < lines.length; idx++) {
            String line = lines[idx];
            String trimmed = line.trim();
            if (depth == 0 && isConditionalDirective(trimmed)) {
                hoisted.append(line).append('\n');
            }

            if (depth == 0 && isDeclarationCandidate(trimmed)) {
                Matcher starter = GLOBAL_INIT_START.matcher(line);
                if (starter.matches()) {
                    Initializer init = collectInitializer(lines, idx, starter.group(4));
                    if (init != null && !isConstantExpression(init.expression)) {
                        // Bare declaration on the first line; blank continuation lines keep GLSL error line
                        // numbers aligned with the dumped source.
                        body.append(starter.group(1)).append(starter.group(2)).append(' ')
                                .append(starter.group(3)).append(";\n");
                        for (int k = idx + 1; k <= init.endLine; k++) {
                            body.append('\n');
                        }
                        hoisted.append("    ").append(starter.group(3)).append(" = ")
                                .append(init.expression).append(";\n");
                        hasHoistedAssignments = true;
                        for (int k = idx; k <= init.endLine; k++) {
                            depth += braceDelta(lines[k]);
                        }
                        idx = init.endLine;
                        continue;
                    }
                }
            }

            body.append(line).append('\n');
            depth += braceDelta(line);
        }
        return new Result(body.toString(), hasHoistedAssignments ? hoisted.toString() : "");
    }

    /** A collected initializer: its expression text (sans trailing {@code ;}) and the last source line it occupies. */
    private static final class Initializer {
        final String expression;
        final int endLine;

        Initializer(String expression, int endLine) {
            this.expression = expression;
            this.endLine = endLine;
        }
    }

    /**
     * Accumulates the initializer expression starting from {@code firstRemainder} (the text after {@code =} on
     * {@code lines[startLine]}), continuing across lines until the statement-terminating {@code ;} at paren/bracket/brace
     * nesting 0. Returns {@code null} if the statement never terminates, or if code other than a comment follows the
     * terminator on its line (the caller then leaves the declaration untouched rather than dropping that trailing code).
     */
    private static Initializer collectInitializer(String[] lines, int startLine, String firstRemainder) {
        StringBuilder expression = new StringBuilder();
        int nesting = 0;
        String remainder = firstRemainder;
        for (int lineIndex = startLine; lineIndex < lines.length; lineIndex++) {
            for (int i = 0; i < remainder.length(); i++) {
                char c = remainder.charAt(i);
                if (c == '(' || c == '[' || c == '{') {
                    nesting++;
                } else if (c == ')' || c == ']' || c == '}') {
                    nesting--;
                } else if (c == ';' && nesting == 0) {
                    if (!TRAILING_AFTER_TERMINATOR.matcher(remainder.substring(i + 1)).matches()) {
                        return null;
                    }
                    return new Initializer(expression.toString().trim(), lineIndex);
                }
                expression.append(c);
            }
            expression.append('\n');
            if (lineIndex + 1 < lines.length) {
                remainder = lines[lineIndex + 1];
            }
        }
        return null;
    }

    private static boolean isDeclarationCandidate(String trimmed) {
        return !trimmed.isEmpty()
                && !trimmed.startsWith("const")
                && !trimmed.startsWith("#")
                && !trimmed.startsWith("//")
                && !trimmed.startsWith("uniform")
                && !trimmed.startsWith("varying")
                && !trimmed.startsWith("attribute")
                && !trimmed.startsWith("in ")
                && !trimmed.startsWith("out ");
    }

    private static int braceDelta(String line) {
        int delta = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') {
                delta++;
            } else if (c == '}') {
                delta--;
            }
        }
        return delta;
    }

    private static boolean isConditionalDirective(String trimmed) {
        if (!trimmed.startsWith("#")) {
            return false;
        }
        String directive = trimmed.substring(1).trim();
        return startsDirective(directive, "if")
                || startsDirective(directive, "ifdef")
                || startsDirective(directive, "ifndef")
                || startsDirective(directive, "elif")
                || directive.equals("else")
                || directive.equals("endif");
    }

    private static boolean startsDirective(String directive, String keyword) {
        if (!directive.startsWith(keyword)) {
            return false;
        }
        if (directive.length() == keyword.length()) {
            return true;
        }
        char next = directive.charAt(keyword.length());
        return next == '(' || Character.isWhitespace(next);
    }

    private static boolean isConstantExpression(String expression) {
        Matcher identifiers = IDENTIFIER.matcher(expression);
        while (identifiers.find()) {
            if (!CONSTANT_CALLEES.matcher(identifiers.group()).matches()) {
                return false;
            }
        }
        return true;
    }
}
