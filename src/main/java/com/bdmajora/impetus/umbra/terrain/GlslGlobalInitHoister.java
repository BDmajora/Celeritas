package com.bdmajora.impetus.umbra.terrain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Moves non-constant global-variable initialisers into the generated main()
//
// GLSL requires a global initialiser to be a constant expression, but legacy packs freely write things like
// `float comp = 1.0 - near/far/far;` at global scope. That is legal in practice under #version 120, where drivers
// evaluate it per invocation. Under #version 330 NVIDIA accepts it silently but may evaluate it BEFORE the uniforms
// are loaded, producing zeros and NaNs that quietly corrupt whole passes — this exact class of bug is what made
// terrain render nothing until the prologue matrices were converted to defines
// Hoisting the assignment into main() reproduces the 120 semantics exactly
//
// The transform is line-based with brace-depth tracking. Only depth-0, single-declarator initialisers of simple
// types are touched, and only when the initialiser references an identifier that is not a literal or a type
// constructor. Moving a genuinely constant initialiser would be safe too; the whitelist just keeps the churn down
//
// An initialiser expression may span several lines, and it must be collected whole. BSL writes
// `weatherCol = mix( ... )` across nine lines, and hoisting only its single-line neighbours (weatherRain,
// weatherWeight) while leaving weatherCol at global scope makes it evaluate against still-zero inputs, giving
// vec4(0), which collapses lightCol and ambientCol to black — terrain goes near-black in rain. So the collector
// accumulates continuation lines up to the statement-terminating ; at paren/bracket/brace nesting 0
//
// Top-level preprocessor conditionals are mirrored into the hoisted assignment stream. Packs such as Sildur's put
// non-constant globals inside #ifdef option gates, and moving the assignments outside those gates would make the
// generated main() reference declarations the GLSL preprocessor had already removed
public final class GlslGlobalInitHoister {
    // Matches only the START of a global initialiser — indent, type, name, =, and whatever follows on that first
    // line. The rest is collected by hand, because a regex cannot balance the nesting a multi-line initialiser has
    private static final Pattern GLOBAL_INIT_START = Pattern.compile(
            "^(\\s*)(float|int|bool|vec[234]|ivec[234]|mat[234])\\s+(\\w+)\\s*=\\s*(.*)$");
    private static final Pattern TRAILING_AFTER_TERMINATOR = Pattern.compile("\\s*(?://.*)?");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern CONSTANT_CALLEES = Pattern.compile(
            "vec[234]|ivec[234]|mat[234]|float|int|bool|true|false");

    // The two halves the caller needs: the rewritten source with initialisers stripped down to bare declarations,
    // and the assignment statements to splice in at the top of the generated main()
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

    // One collected initialiser: the expression text without its trailing ;, and the index of the last source line
    // it occupied — the caller needs that to know how many lines to consume
    private static final class Initializer {
        final String expression;
        final int endLine;

        Initializer(String expression, int endLine) {
            this.expression = expression;
            this.endLine = endLine;
        }
    }

    // Accumulates the initialiser expression, starting from the text after the = on the first line and continuing
    // across lines until the statement-terminating ; at paren/bracket/brace nesting 0
    // Returns null in two cases, and both mean "leave this declaration alone": the statement never terminates, or
    // real code follows the terminator on its line. The second matters because hoisting would otherwise drop that
    // trailing code entirely
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
