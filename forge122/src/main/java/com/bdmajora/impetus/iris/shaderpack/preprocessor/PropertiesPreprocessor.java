package com.bdmajora.impetus.iris.shaderpack.preprocessor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates the C-preprocessor conditionals OptiFine allows in {@code *.properties} files
 * ({@code #if MC_VERSION >= 11300} … {@code #else} … {@code #endif}), producing the flattened property lines for the
 * active define set. The Impetus counterpart of Iris's JCPP-backed {@code PropertiesPreprocessor}, self-contained
 * because properties files only ever use integer-comparison conditionals — no token pasting, no function macros.
 * <p>
 * Supported directives: {@code #if}, {@code #ifdef}, {@code #ifndef}, {@code #elif}, {@code #else}, {@code #endif}.
 * Expressions support integer literals, define names (their value parsed as an integer, {@code 0} if undefined or
 * non-numeric), {@code defined(NAME)}/{@code defined NAME}, parentheses, {@code !}, unary {@code -}/{@code +},
 * {@code * / %}, {@code + -}, comparisons, {@code ==}/{@code !=}, {@code &&}, {@code ||}. Any other {@code #} line is
 * a comment and is dropped; backslash line continuations are joined before processing.
 */
public final class PropertiesPreprocessor {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    private PropertiesPreprocessor() {
    }

    public static String preprocess(String source, Map<String, String> defines) {
        List<String> logicalLines = joinContinuations(source);
        StringBuilder out = new StringBuilder(source.length());
        // Track #define/#undef in ACTIVE regions so cascading defines (GLSL settings files, option macros that
        // derive other macros) feed later #if evaluation — required when this runs over shader sources to pick the
        // ACTIVE DRAWBUFFERS/RENDERTARGETS variant the way Iris's preprocessed-source directive extraction does.
        defines = new java.util.HashMap<>(defines);

        // Each conditional nesting level: [0] = this branch active, [1] = any branch so far taken.
        Deque<boolean[]> stack = new ArrayDeque<>();

        for (String line : logicalLines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                String directive = stripDirectiveComments(trimmed.substring(1)).trim();
                if (directive.startsWith("if ") || directive.startsWith("if(")) {
                    boolean value = parentActive(stack) && evaluate(directive.substring(2), defines);
                    stack.push(new boolean[]{value, value});
                } else if (directive.startsWith("ifdef ")) {
                    boolean value = parentActive(stack) && defines.containsKey(directive.substring("ifdef ".length()).trim());
                    stack.push(new boolean[]{value, value});
                } else if (directive.startsWith("ifndef ")) {
                    boolean value = parentActive(stack) && !defines.containsKey(directive.substring("ifndef ".length()).trim());
                    stack.push(new boolean[]{value, value});
                } else if (directive.startsWith("elif")) {
                    boolean[] frame = stack.peek();
                    if (frame == null) {
                        LOGGER.warn("[Iris] #elif without #if in properties file; ignoring");
                        continue;
                    }
                    stack.pop();
                    boolean value = !frame[1] && parentActive(stack)
                            && evaluate(directive.substring("elif".length()), defines);
                    stack.push(new boolean[]{value, frame[1] || value});
                } else if (directive.equals("else")) {
                    boolean[] frame = stack.peek();
                    if (frame == null) {
                        LOGGER.warn("[Iris] #else without #if in properties file; ignoring");
                        continue;
                    }
                    stack.pop();
                    boolean value = !frame[1] && parentActive(stack);
                    stack.push(new boolean[]{value, true});
                } else if (directive.startsWith("define ")) {
                    if (parentActive(stack) && (stack.isEmpty() || stack.peek()[0])) {
                        String body = directive.substring("define ".length()).trim();
                        int space = body.indexOf(' ');
                        int paren = body.indexOf('(');
                        if (paren >= 0 && (space < 0 || paren < space)) {
                            // Function-like macro: track presence only (defined() checks), value unusable in #if.
                            defines.put(body.substring(0, paren), "");
                        } else if (space < 0) {
                            defines.put(body, "");
                        } else {
                            defines.put(body.substring(0, space), body.substring(space + 1).trim());
                        }
                    }
                    continue;
                } else if (directive.startsWith("undef ")) {
                    if (parentActive(stack) && (stack.isEmpty() || stack.peek()[0])) {
                        defines.remove(directive.substring("undef ".length()).trim());
                    }
                    continue;
                } else if (directive.equals("endif")) {
                    if (stack.isEmpty()) {
                        LOGGER.warn("[Iris] #endif without #if in properties file; ignoring");
                    } else {
                        stack.pop();
                    }
                }
                // Any other #-line is a properties comment; drop it either way.
                continue;
            }

            if (parentActive(stack) && (stack.isEmpty() || stack.peek()[0])) {
                out.append(line).append('\n');
            }
        }

        if (!stack.isEmpty()) {
            LOGGER.warn("[Iris] Unterminated #if in properties file ({} level(s) open at EOF)", stack.size());
        }
        return out.toString();
    }

    /**
     * JCPP ignores comments before it evaluates preprocessor directives. Shader-pack properties commonly use
     * {@code #if OPTION // label} and {@code #endif // label}; feeding the comments into the expression parser makes
     * otherwise-valid packs look unterminated.
     */
    private static String stripDirectiveComments(String directive) {
        StringBuilder out = new StringBuilder(directive.length());
        for (int i = 0; i < directive.length(); i++) {
            char c = directive.charAt(i);
            if (c == '/' && i + 1 < directive.length()) {
                char next = directive.charAt(i + 1);
                if (next == '/') {
                    break;
                }
                if (next == '*') {
                    i += 2;
                    while (i + 1 < directive.length()
                            && !(directive.charAt(i) == '*' && directive.charAt(i + 1) == '/')) {
                        i++;
                    }
                    if (i + 1 < directive.length()) {
                        i++;
                    }
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Whether every enclosing conditional level above the current one is active. */
    private static boolean parentActive(Deque<boolean[]> stack) {
        for (boolean[] frame : stack) {
            if (!frame[0]) {
                return false;
            }
        }
        return true;
    }

    /** Splits into lines, joining {@code \}-continued lines the way {@link java.util.Properties} would. */
    private static List<String> joinContinuations(String source) {
        String[] rawLines = source.split("\r\n|\r|\n", -1);
        List<String> lines = new ArrayList<>(rawLines.length);
        int i = 0;
        while (i < rawLines.length) {
            String line = rawLines[i++];
            while (endsWithOddBackslashes(line) && i < rawLines.length) {
                line = line.substring(0, line.length() - 1) + " " + rawLines[i++].trim();
            }
            if (endsWithOddBackslashes(line)) {
                line = line.substring(0, line.length() - 1);
            }
            lines.add(line);
        }
        return lines;
    }

    private static boolean endsWithOddBackslashes(String line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            count++;
        }
        return (count & 1) == 1;
    }

    // ------------------------------------------------------------------ expression evaluation

    private static boolean evaluate(String expression, Map<String, String> defines) {
        return evaluateBooleanExpression(expression, defines).orElse(false);
    }

    public static Optional<Boolean> evaluateBooleanExpression(String expression, Map<String, String> defines) {
        try {
            return Optional.of(new ExpressionParser(expression, defines).parse() != 0);
        } catch (RuntimeException e) {
            LOGGER.warn("[Iris] Failed to evaluate properties conditional \"#if{}\": {}", expression, e.getMessage());
            return Optional.empty();
        }
    }

    /** Minimal recursive-descent parser over the C-preprocessor integer expression grammar. */
    private static final class ExpressionParser {
        private final String text;
        private final Map<String, String> defines;
        private final int defineDepth;
        private int pos;

        ExpressionParser(String text, Map<String, String> defines) {
            this(text, defines, 0);
        }

        private ExpressionParser(String text, Map<String, String> defines, int defineDepth) {
            this.text = text;
            this.defines = defines;
            this.defineDepth = defineDepth;
        }

        long parse() {
            long value = parseOr();
            skipWhitespace();
            if (this.pos < this.text.length()) {
                throw new IllegalArgumentException("Trailing input at position " + this.pos);
            }
            return value;
        }

        private long parseOr() {
            long left = parseAnd();
            while (eat("||")) {
                long right = parseAnd();
                left = (left != 0 || right != 0) ? 1 : 0;
            }
            return left;
        }

        private long parseAnd() {
            long left = parseEquality();
            while (eat("&&")) {
                long right = parseEquality();
                left = (left != 0 && right != 0) ? 1 : 0;
            }
            return left;
        }

        private long parseEquality() {
            long left = parseComparison();
            while (true) {
                if (eat("==")) {
                    left = left == parseComparison() ? 1 : 0;
                } else if (eat("!=")) {
                    left = left != parseComparison() ? 1 : 0;
                } else {
                    return left;
                }
            }
        }

        private long parseComparison() {
            long left = parseAdditive();
            while (true) {
                if (eat("<=")) {
                    left = left <= parseAdditive() ? 1 : 0;
                } else if (eat(">=")) {
                    left = left >= parseAdditive() ? 1 : 0;
                } else if (eat("<")) {
                    left = left < parseAdditive() ? 1 : 0;
                } else if (eat(">")) {
                    left = left > parseAdditive() ? 1 : 0;
                } else {
                    return left;
                }
            }
        }

        private long parseAdditive() {
            long left = parseMultiplicative();
            while (true) {
                if (eat("+")) {
                    left += parseMultiplicative();
                } else if (eatMinus()) {
                    left -= parseMultiplicative();
                } else {
                    return left;
                }
            }
        }

        private long parseMultiplicative() {
            long left = parseUnary();
            while (true) {
                if (eat("*")) {
                    left *= parseUnary();
                } else if (eat("/")) {
                    long right = parseUnary();
                    left = right == 0 ? 0 : left / right;
                } else if (eat("%")) {
                    long right = parseUnary();
                    left = right == 0 ? 0 : left % right;
                } else {
                    return left;
                }
            }
        }

        private long parseUnary() {
            skipWhitespace();
            if (eatChar('!')) {
                return parseUnary() == 0 ? 1 : 0;
            }
            if (eatChar('-')) {
                return -parseUnary();
            }
            if (eatChar('+')) {
                return parseUnary();
            }
            return parsePrimary();
        }

        private long parsePrimary() {
            skipWhitespace();
            if (eatChar('(')) {
                long value = parseOr();
                skipWhitespace();
                if (!eatChar(')')) {
                    throw new IllegalArgumentException("Missing ')'");
                }
                return value;
            }
            if (this.pos >= this.text.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }
            char c = this.text.charAt(this.pos);
            if (Character.isDigit(c)) {
                int start = this.pos;
                while (this.pos < this.text.length() && Character.isLetterOrDigit(this.text.charAt(this.pos))) {
                    this.pos++;
                }
                String number = this.text.substring(start, this.pos);
                try {
                    return Long.decode(number);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Bad number: " + number);
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int start = this.pos;
                while (this.pos < this.text.length()
                        && (Character.isLetterOrDigit(this.text.charAt(this.pos)) || this.text.charAt(this.pos) == '_')) {
                    this.pos++;
                }
                String name = this.text.substring(start, this.pos);
                if (name.equals("defined")) {
                    skipWhitespace();
                    boolean parens = eatChar('(');
                    skipWhitespace();
                    int identStart = this.pos;
                    while (this.pos < this.text.length()
                            && (Character.isLetterOrDigit(this.text.charAt(this.pos)) || this.text.charAt(this.pos) == '_')) {
                        this.pos++;
                    }
                    String ident = this.text.substring(identStart, this.pos);
                    if (parens) {
                        skipWhitespace();
                        if (!eatChar(')')) {
                            throw new IllegalArgumentException("Missing ')' after defined(" + ident);
                        }
                    }
                    return this.defines.containsKey(ident) ? 1 : 0;
                }
                return resolveDefine(name);
            }
            throw new IllegalArgumentException("Unexpected character '" + c + "'");
        }

        private long resolveDefine(String name) {
            String value = this.defines.get(name);
            if (value == null || value.isEmpty() || this.defineDepth >= 8) {
                return 0;
            }
            String trimmed = value.trim();
            try {
                return Long.decode(trimmed);
            } catch (NumberFormatException ignored) {
                try {
                    return new ExpressionParser(trimmed, this.defines, this.defineDepth + 1).parse();
                } catch (IllegalArgumentException e) {
                    return 0;
                }
            }
        }

        /** Consumes the operator if present. Guards {@code <}/{@code >} against their {@code <=}/{@code >=} forms. */
        private boolean eat(String op) {
            skipWhitespace();
            if (!this.text.startsWith(op, this.pos)) {
                return false;
            }
            if ((op.equals("<") || op.equals(">"))
                    && this.pos + 1 < this.text.length() && this.text.charAt(this.pos + 1) == '=') {
                return false;
            }
            this.pos += op.length();
            return true;
        }

        /** {@code -} needs care so it is not confused with a unary minus after another operator; here it never is. */
        private boolean eatMinus() {
            skipWhitespace();
            if (this.pos < this.text.length() && this.text.charAt(this.pos) == '-') {
                this.pos++;
                return true;
            }
            return false;
        }

        private boolean eatChar(char c) {
            skipWhitespace();
            if (this.pos < this.text.length() && this.text.charAt(this.pos) == c) {
                this.pos++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (this.pos < this.text.length() && Character.isWhitespace(this.text.charAt(this.pos))) {
                this.pos++;
            }
        }
    }
}
