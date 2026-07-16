package com.bdmajora.impetus.iris.terrain;

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
 * Line-based with brace-depth tracking: only depth-0, single-declarator, single-line initializers of simple types are
 * touched, and only when the initializer references an identifier that is not a literal/type-constructor (moving a
 * genuinely constant initializer would also be safe — the whitelist just minimizes churn).
 */
public final class GlslGlobalInitHoister {
    private static final Pattern GLOBAL_INIT = Pattern.compile(
            "^(\\s*)(float|int|bool|vec[234]|ivec[234]|mat[234])\\s+(\\w+)\\s*=\\s*([^;]+);\\s*(?://.*)?$");
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

        for (String line : source.split("\n", -1)) {
            boolean rewritten = false;
            String trimmed = line.trim();
            if (depth == 0
                    && !trimmed.isEmpty()
                    && !trimmed.startsWith("const")
                    && !trimmed.startsWith("#")
                    && !trimmed.startsWith("//")
                    && !trimmed.startsWith("uniform")
                    && !trimmed.startsWith("varying")
                    && !trimmed.startsWith("attribute")
                    && !trimmed.startsWith("in ")
                    && !trimmed.startsWith("out ")) {
                Matcher matcher = GLOBAL_INIT.matcher(line);
                if (matcher.matches() && !isConstantExpression(matcher.group(4))) {
                    body.append(matcher.group(1)).append(matcher.group(2)).append(' ')
                            .append(matcher.group(3)).append(";");
                    hoisted.append("    ").append(matcher.group(3)).append(" = ")
                            .append(matcher.group(4)).append(";\n");
                    rewritten = true;
                }
            }
            if (!rewritten) {
                body.append(line);
            }
            body.append('\n');

            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
        }
        return new Result(body.toString(), hoisted.toString());
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
