package org.taumc.celeritas.iris.shaderpack.preprocessor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the C-preprocessor conditionals OptiFine allows in {@code *.properties} files
 * ({@code #if MC_VERSION >= 11300} … {@code #else} … {@code #endif}), producing the flattened property lines for the
 * active define set. The Celeritas counterpart of Iris's JCPP-backed {@code PropertiesPreprocessor}, self-contained
 * because properties files only ever use integer-comparison conditionals — no token pasting, no function macros.
 * <p>
 * Supported directives: {@code #if}, {@code #ifdef}, {@code #ifndef}, {@code #elif}, {@code #else}, {@code #endif}.
 * Expressions support integer literals, define names (their value parsed as an integer, {@code 0} if undefined or
 * non-numeric), {@code defined(NAME)}/{@code defined NAME}, parentheses, {@code !}, unary {@code -}/{@code +},
 * {@code * / %}, {@code + -}, comparisons, {@code ==}/{@code !=}, {@code &&}, {@code ||}. Any other {@code #} line is
 * a comment and is dropped; backslash line continuations are joined before processing.
 */
public final class PropertiesPreprocessor {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Iris");

    private PropertiesPreprocessor() {
    }

    public static String preprocess(String source, Map<String, String> defines) {
        List<String> logicalLines = joinContinuations(source);
        StringBuilder out = new StringBuilder(source.length());

        // Each conditional nesting level: [0] = this branch active, [1] = any branch so far taken.
        Deque<boolean[]> stack = new ArrayDeque<>();

        for (String line : logicalLines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                String directive = trimmed.substring(1).trim();
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
        try {
            return new ExpressionParser(expression, defines).parse() != 0;
        } catch (RuntimeException e) {
            LOGGER.warn("[Iris] Failed to evaluate properties conditional \"#if{}\": {}", expression, e.getMessage());
            return false;
        }
    }

    /** Minimal recursive-descent parser over the C-preprocessor integer expression grammar. */
    private static final class ExpressionParser {
        private final String text;
        private final Map<String, String> defines;
        private int pos;

        ExpressionParser(String text, Map<String, String> defines) {
            this.text = text;
            this.defines = defines;
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
                String value = this.defines.get(name);
                if (value == null || value.isEmpty()) {
                    return 0;
                }
                try {
                    return Long.decode(value.trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            throw new IllegalArgumentException("Unexpected character '" + c + "'");
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
