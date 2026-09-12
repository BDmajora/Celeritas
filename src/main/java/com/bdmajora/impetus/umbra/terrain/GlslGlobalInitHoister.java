package com.bdmajora.impetus.umbra.terrain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Moves non-constant global initialisers into main(), reproducing #version 120 semantics under 330 where NVIDIA may evaluate them before uniforms load; multi-line initialisers are collected whole and top-level #ifdef gates are mirrored
public final class GlslGlobalInitHoister {
    // Matches only the START of a global initialiser (indent, type, name, =, rest of first line); the remainder is collected by hand since a regex cannot balance multi-line nesting
    private static final Pattern GLOBAL_INIT_START = Pattern.compile(
            "^(\\s*)(float|int|bool|vec[234]|ivec[234]|mat[234])\\s+(\\w+)\\s*=\\s*(.*)$");
    private static final Pattern TRAILING_AFTER_TERMINATOR = Pattern.compile("\\s*(?://.*)?");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern CONSTANT_CALLEES = Pattern.compile(
            "vec[234]|ivec[234]|mat[234]|float|int|bool|true|false");

    // The two halves the caller needs: the rewritten source with initialisers stripped to bare declarations, and the assignments to splice into the generated main()
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

    // Returns the rewritten globals and the assignment block to inject at the top of main
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
                        // Bare declaration on the first line; blank continuation lines keep GLSL error line numbers aligned with the dumped source
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

    // One collected initialiser: the expression without its trailing ;, and the index of its last source line so the caller knows how many lines to consume
    private static final class Initializer {
        final String expression;
        final int endLine;

        Initializer(String expression, int endLine) {
            this.expression = expression;
            this.endLine = endLine;
        }
    }

    // Accumulates the initialiser from after the = across lines until the ; at nesting 0; null when the statement never terminates or real code follows the terminator on its line, both meaning leave it alone since hoisting would drop that trailing code
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

    // Simple-typed, single-declarator, initialised, at depth zero
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

    // Net brace count on a line, ignoring string and comment content
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

    // #if, #ifdef, #ifndef, #else, #elif or #endif
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

    // Directive match tolerant of whitespace after the hash
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

    // Literals and type constructors only; anything referencing an identifier is not
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
