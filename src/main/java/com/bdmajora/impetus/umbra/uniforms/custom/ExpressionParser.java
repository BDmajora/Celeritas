package com.bdmajora.impetus.umbra.uniforms.custom;

import java.util.ArrayList;
import java.util.List;

/**
 * A compact recursive-descent parser for the custom-uniform expression language used by OptiFine/Umbra shader
 * packs (arithmetic, comparisons, boolean logic, ternary/if, a standard maths function set, and vec2/3/4
 * constructors). Original implementation for Impetus.
 *
 * <p>Grammar (loosely), lowest precedence first:
 * <pre>
 *   expr    := ternary
 *   ternary := or ( '?' expr ':' expr )?
 *   or      := and ( '||' and )*
 *   and     := equality ( '&amp;&amp;' equality )*
 *   equality:= comparison ( ('=='|'!=') comparison )*
 *   compare := additive ( ('&lt;'|'&gt;'|'&lt;='|'&gt;=') additive )*
 *   additive:= term ( ('+'|'-') term )*
 *   term    := unary ( ('*'|'/'|'%') unary )*
 *   unary   := ('-'|'!')* primary
 *   primary := number | ident | ident '(' args ')' | '(' expr ')'
 * </pre>
 */
public final class ExpressionParser {
    public static final class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    private final String source;
    private int pos;
    /** Running count of {@code smooth()} calls, so each gets a stable persistent-state slot. */
    private int smoothCallCount;

    private ExpressionParser(String source) {
        this.source = source;
    }

    public static final class Result {
        public final CompiledExpression expression;
        public final int smoothCallCount;

        Result(CompiledExpression expression, int smoothCallCount) {
            this.expression = expression;
            this.smoothCallCount = smoothCallCount;
        }
    }

    public static Result parse(String source) {
        ExpressionParser parser = new ExpressionParser(source);
        CompiledExpression expr = parser.parseExpression();
        parser.skipWhitespace();
        if (parser.pos < parser.source.length()) {
            throw new ParseException("Unexpected trailing input at " + parser.pos + " in: " + source);
        }
        return new Result(expr, parser.smoothCallCount);
    }

    private CompiledExpression parseExpression() {
        return parseTernary();
    }

    private CompiledExpression parseTernary() {
        CompiledExpression condition = parseOr();
        skipWhitespace();
        if (consume('?')) {
            CompiledExpression whenTrue = parseExpression();
            skipWhitespace();
            expect(':');
            CompiledExpression whenFalse = parseExpression();
            return ctx -> condition.evaluate(ctx).asBoolean() ? whenTrue.evaluate(ctx) : whenFalse.evaluate(ctx);
        }
        return condition;
    }

    private CompiledExpression parseOr() {
        CompiledExpression left = parseAnd();
        while (true) {
            skipWhitespace();
            if (consumeSequence("||")) {
                CompiledExpression right = parseAnd();
                CompiledExpression l = left;
                left = ctx -> CustomUniformValue.bool(l.evaluate(ctx).asBoolean() || right.evaluate(ctx).asBoolean());
            } else {
                return left;
            }
        }
    }

    private CompiledExpression parseAnd() {
        CompiledExpression left = parseEquality();
        while (true) {
            skipWhitespace();
            if (consumeSequence("&&")) {
                CompiledExpression right = parseEquality();
                CompiledExpression l = left;
                left = ctx -> CustomUniformValue.bool(l.evaluate(ctx).asBoolean() && right.evaluate(ctx).asBoolean());
            } else {
                return left;
            }
        }
    }

    private CompiledExpression parseEquality() {
        CompiledExpression left = parseComparison();
        while (true) {
            skipWhitespace();
            if (consumeSequence("==")) {
                left = binary(left, parseComparison(), (a, b) -> a == b ? 1.0 : 0.0);
            } else if (consumeSequence("!=")) {
                left = binary(left, parseComparison(), (a, b) -> a != b ? 1.0 : 0.0);
            } else {
                return left;
            }
        }
    }

    private CompiledExpression parseComparison() {
        CompiledExpression left = parseAdditive();
        while (true) {
            skipWhitespace();
            if (consumeSequence("<=")) {
                left = binary(left, parseAdditive(), (a, b) -> a <= b ? 1.0 : 0.0);
            } else if (consumeSequence(">=")) {
                left = binary(left, parseAdditive(), (a, b) -> a >= b ? 1.0 : 0.0);
            } else if (peek() == '<') {
                pos++;
                left = binary(left, parseAdditive(), (a, b) -> a < b ? 1.0 : 0.0);
            } else if (peek() == '>') {
                pos++;
                left = binary(left, parseAdditive(), (a, b) -> a > b ? 1.0 : 0.0);
            } else {
                return left;
            }
        }
    }

    private CompiledExpression parseAdditive() {
        CompiledExpression left = parseTerm();
        while (true) {
            skipWhitespace();
            char c = peek();
            if (c == '+') {
                pos++;
                left = binary(left, parseTerm(), Double::sum);
            } else if (c == '-') {
                pos++;
                left = binary(left, parseTerm(), (a, b) -> a - b);
            } else {
                return left;
            }
        }
    }

    private CompiledExpression parseTerm() {
        CompiledExpression left = parseUnary();
        while (true) {
            skipWhitespace();
            char c = peek();
            if (c == '*') {
                pos++;
                left = binary(left, parseUnary(), (a, b) -> a * b);
            } else if (c == '/') {
                pos++;
                left = binary(left, parseUnary(), (a, b) -> a / b);
            } else if (c == '%') {
                pos++;
                left = binary(left, parseUnary(), (a, b) -> a % b);
            } else {
                return left;
            }
        }
    }

    private CompiledExpression parseUnary() {
        skipWhitespace();
        char c = peek();
        if (c == '-') {
            pos++;
            CompiledExpression operand = parseUnary();
            return ctx -> operand.evaluate(ctx).map(v -> -v);
        }
        if (c == '!') {
            pos++;
            CompiledExpression operand = parseUnary();
            return ctx -> CustomUniformValue.bool(!operand.evaluate(ctx).asBoolean());
        }
        return parsePrimary();
    }

    private CompiledExpression parsePrimary() {
        skipWhitespace();
        char c = peek();

        if (c == '(') {
            pos++;
            CompiledExpression inner = parseExpression();
            skipWhitespace();
            expect(')');
            return inner;
        }

        if (Character.isDigit(c) || c == '.') {
            return parseNumber();
        }

        if (Character.isLetter(c) || c == '_') {
            String ident = parseIdentifier();
            skipWhitespace();
            if (peek() == '(') {
                return parseCall(ident);
            }
            // Constants and boolean literals.
            switch (ident) {
                case "true":
                    return ctx -> CustomUniformValue.bool(true);
                case "false":
                    return ctx -> CustomUniformValue.bool(false);
                case "pi":
                case "PI":
                    return ctx -> CustomUniformValue.scalar((float) Math.PI);
                default:
                    Float constant = namedConstant(ident);
                    if (constant != null) {
                        return ctx -> CustomUniformValue.scalar(constant);
                    }
                    // Component/swizzle access (eyeBrightness.y, cameraPosition.x, delta.xz): the identifier
                    // grammar swallows the dots, so decompose here. Without this the whole dotted name misses the
                    // input table and SILENTLY evaluates to 0 — which zeroed eyeBrightness-derived custom uniforms
                    // (SDV's eyeSkylight, MakeUp's exposure) and rendered those packs cave-black.
                    int dot = ident.indexOf('.');
                    if (dot > 0) {
                        String base = ident.substring(0, dot);
                        int[] indices = swizzleIndices(ident.substring(dot + 1));
                        if (indices != null) {
                            return ctx -> {
                                CustomUniformValue whole = ctx.resolve(ident);
                                if (whole != null) {
                                    return whole;
                                }
                                CustomUniformValue value = ctx.resolve(base);
                                if (value == null) {
                                    return CustomUniformValue.scalar(0.0f);
                                }
                                float[] out = new float[indices.length];
                                for (int i = 0; i < indices.length; i++) {
                                    out[i] = indices[i] < value.width ? value.components[indices[i]] : 0.0f;
                                }
                                return CustomUniformValue.of(out);
                            };
                        }
                    }
                    return ctx -> {
                        CustomUniformValue value = ctx.resolve(ident);
                        return value != null ? value : CustomUniformValue.scalar(0.0f);
                    };
            }
        }

        throw new ParseException("Unexpected character '" + c + "' at " + pos + " in: " + source);
    }

    private CompiledExpression parseNumber() {
        int start = pos;
        while (pos < source.length() && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.'
                || source.charAt(pos) == 'e' || source.charAt(pos) == 'E'
                || ((source.charAt(pos) == '+' || source.charAt(pos) == '-') && pos > start
                && (source.charAt(pos - 1) == 'e' || source.charAt(pos - 1) == 'E')))) {
            pos++;
        }
        float value = Float.parseFloat(source.substring(start, pos));
        return ctx -> CustomUniformValue.scalar(value);
    }

    private CompiledExpression parseCall(String name) {
        expect('(');
        List<CompiledExpression> args = new ArrayList<>();
        skipWhitespace();
        if (peek() != ')') {
            args.add(parseExpression());
            skipWhitespace();
            while (consume(',')) {
                args.add(parseExpression());
                skipWhitespace();
            }
        }
        expect(')');
        return buildFunction(name, args);
    }

    private CompiledExpression buildFunction(String name, List<CompiledExpression> args) {
        switch (name) {
            case "vec2":
                return vector(args, 2);
            case "vec3":
                return vector(args, 3);
            case "vec4":
                return vector(args, 4);
            case "if":
                return buildIf(args);
            case "min":
                requireArity(name, args, 2);
                return ctx -> CustomUniformValue.combine(args.get(0).evaluate(ctx), args.get(1).evaluate(ctx), Math::min);
            case "max":
                requireArity(name, args, 2);
                return ctx -> CustomUniformValue.combine(args.get(0).evaluate(ctx), args.get(1).evaluate(ctx), Math::max);
            case "mod":
            case "fmod":
                requireArity(name, args, 2);
                return ctx -> CustomUniformValue.combine(args.get(0).evaluate(ctx), args.get(1).evaluate(ctx), (a, b) -> a % b);
            case "pow":
                requireArity(name, args, 2);
                return ctx -> CustomUniformValue.combine(args.get(0).evaluate(ctx), args.get(1).evaluate(ctx), Math::pow);
            case "atan2":
                requireArity(name, args, 2);
                return ctx -> CustomUniformValue.combine(args.get(0).evaluate(ctx), args.get(1).evaluate(ctx), Math::atan2);
            case "clamp":
                requireArity(name, args, 3);
                return ctx -> {
                    CustomUniformValue v = args.get(0).evaluate(ctx);
                    CustomUniformValue lo = args.get(1).evaluate(ctx);
                    CustomUniformValue hi = args.get(2).evaluate(ctx);
                    return CustomUniformValue.combine(CustomUniformValue.combine(v, lo, Math::max), hi, Math::min);
                };
            case "mix":
                requireArity(name, args, 3);
                return ctx -> {
                    CustomUniformValue a = args.get(0).evaluate(ctx);
                    CustomUniformValue b = args.get(1).evaluate(ctx);
                    CustomUniformValue t = args.get(2).evaluate(ctx);
                    return CustomUniformValue.combine(a, CustomUniformValue.combine(CustomUniformValue.combine(b, a, (bb, aa) -> bb - aa), t, (d, tt) -> d * tt), Double::sum);
                };
            case "in":
                return buildIn(args);
            case "equals":
                return buildEquals(args);
            case "smooth":
                return buildSmooth(args);
            default:
                return buildUnaryFunction(name, args);
        }
    }

    private CompiledExpression buildIf(List<CompiledExpression> args) {
        if (args.size() < 3 || (args.size() & 1) == 0) {
            throw new ParseException("if() expects condition/value pairs plus a fallback, got " + args.size());
        }
        return ctx -> {
            for (int i = 0; i < args.size() - 1; i += 2) {
                if (args.get(i).evaluate(ctx).asBoolean()) {
                    return args.get(i + 1).evaluate(ctx);
                }
            }
            return args.get(args.size() - 1).evaluate(ctx);
        };
    }

    private CompiledExpression buildUnaryFunction(String name, List<CompiledExpression> args) {
        requireArity(name, args, 1);
        CompiledExpression arg = args.get(0);
        java.util.function.DoubleUnaryOperator op;
        switch (name) {
            case "sin": op = Math::sin; break;
            case "cos": op = Math::cos; break;
            case "tan": op = Math::tan; break;
            case "asin": op = Math::asin; break;
            case "acos": op = Math::acos; break;
            case "atan": op = Math::atan; break;
            case "exp": op = Math::exp; break;
            case "exp2": op = v -> Math.pow(2.0, v); break;
            case "log": op = Math::log; break;
            case "log2": op = v -> Math.log(v) / Math.log(2.0); break;
            case "sqrt": op = Math::sqrt; break;
            case "inversesqrt": op = v -> 1.0 / Math.sqrt(v); break;
            case "abs": op = Math::abs; break;
            case "sign": case "signum": op = Math::signum; break;
            case "floor": op = Math::floor; break;
            case "ceil": op = Math::ceil; break;
            case "round": op = v -> (double) Math.round(v); break;
            case "trunc": op = v -> (double) (long) v; break;
            case "frac": case "fract": op = v -> v - Math.floor(v); break;
            case "radians": op = Math::toRadians; break;
            case "degrees": op = Math::toDegrees; break;
            default:
                throw new ParseException("Unknown function '" + name + "' in: " + source);
        }
        return ctx -> arg.evaluate(ctx).map(op);
    }

    private CompiledExpression buildIn(List<CompiledExpression> args) {
        if (args.size() < 2) {
            throw new ParseException("in() expects at least 2 arguments, got " + args.size());
        }
        CompiledExpression valueExpr = args.get(0);
        List<CompiledExpression> candidates = args.subList(1, args.size());
        return ctx -> {
            float value = valueExpr.evaluate(ctx).x();
            for (CompiledExpression candidate : candidates) {
                if (Math.abs(value - candidate.evaluate(ctx).x()) < 1.0e-5f) {
                    return CustomUniformValue.bool(true);
                }
            }
            return CustomUniformValue.bool(false);
        };
    }

    private CompiledExpression buildEquals(List<CompiledExpression> args) {
        if (args.size() != 2 && args.size() != 3) {
            throw new ParseException("equals() expects 2 or 3 arguments, got " + args.size());
        }
        return ctx -> {
            CustomUniformValue left = args.get(0).evaluate(ctx);
            CustomUniformValue right = args.get(1).evaluate(ctx);
            float epsilon = args.size() == 3 ? Math.abs(args.get(2).evaluate(ctx).x()) : 1.0e-5f;
            int width = Math.max(left.width, right.width);
            for (int i = 0; i < width; i++) {
                if (Math.abs(component(left, i) - component(right, i)) > epsilon) {
                    return CustomUniformValue.bool(false);
                }
            }
            return CustomUniformValue.bool(true);
        };
    }

    private CompiledExpression buildSmooth(List<CompiledExpression> args) {
        // smooth(value[, fadeUp[, fadeDown]]) or OptiFine-style smooth(id, value, fadeUp, fadeDown).
        if (args.isEmpty() || args.size() > 4) {
            throw new ParseException("smooth() takes 1-4 arguments in: " + source);
        }
        int slot = smoothCallCount++;
        int valueIndex = args.size() == 4 ? 1 : 0;
        CompiledExpression valueExpr = args.get(valueIndex);
        CompiledExpression upExpr = args.size() >= valueIndex + 2 ? args.get(valueIndex + 1) : null;
        CompiledExpression downExpr = args.size() >= valueIndex + 3 ? args.get(valueIndex + 2) : upExpr;

        return ctx -> {
            float target = valueExpr.evaluate(ctx).x();
            CustomUniformContext.SmoothState state = ctx.smoothState(slot);
            if (!state.initialized) {
                state.initialized = true;
                state.value = target;
                return CustomUniformValue.scalar(target);
            }
            float up = upExpr != null ? upExpr.evaluate(ctx).x() : 1.0f;
            float down = downExpr != null ? downExpr.evaluate(ctx).x() : up;
            float halfLife = target > state.value ? up : down;
            // Matches Umbra SmoothFloat: shaderpack fade values are tenths of a second.
            float scaledHalfLife = halfLife * 0.1f;
            float rate = scaledHalfLife <= 0.0f ? 1.0f
                    : (float) (1.0 - Math.pow(0.5, ctx.frameTime() / scaledHalfLife));
            state.value += (target - state.value) * rate;
            return CustomUniformValue.scalar(state.value);
        };
    }

    private CompiledExpression vector(List<CompiledExpression> args, int width) {
        return ctx -> {
            float[] out = new float[width];
            int i = 0;
            for (CompiledExpression arg : args) {
                CustomUniformValue v = arg.evaluate(ctx);
                for (int k = 0; k < v.width && i < width; k++) {
                    out[i++] = v.components[k];
                }
            }
            // Broadcast a single scalar across all components (GLSL vecN(scalar) behaviour).
            if (args.size() == 1 && i == 1) {
                for (; i < width; i++) {
                    out[i] = out[0];
                }
            }
            return CustomUniformValue.of(out);
        };
    }

    private static void requireArity(String name, List<CompiledExpression> args, int arity) {
        if (args.size() != arity) {
            throw new ParseException(name + "() expects " + arity + " arguments, got " + args.size());
        }
    }

    private static float component(CustomUniformValue value, int index) {
        return value.width == 1 ? value.components[0] : value.components[Math.min(index, value.width - 1)];
    }

    private CompiledExpression binary(CompiledExpression left, CompiledExpression right, java.util.function.DoubleBinaryOperator op) {
        return ctx -> CustomUniformValue.combine(left.evaluate(ctx), right.evaluate(ctx), op);
    }

    private static Float namedConstant(String name) {
        switch (name) {
            case "PPT_NONE":
                return 0.0f;
            case "PPT_RAIN":
                return 1.0f;
            case "PPT_SNOW":
                return 2.0f;
            case "BIOME_NETHER_WASTES":
                return 8.0f;
            case "BIOME_CRIMSON_FOREST":
            case "BIOME_WARPED_FOREST":
            case "BIOME_BASALT_DELTAS":
            case "BIOME_SOUL_SAND_VALLEY":
            case "BIOME_PALE_GARDEN":
                return -1.0f;
            default:
                return null;
        }
    }

    // --- lexer helpers ---

    /**
     * Maps a swizzle suffix ({@code y}, {@code xz}, {@code rgb}) to component indices, or {@code null} when the
     * suffix is not a pure swizzle (e.g. matrix cell access like {@code m.0.1}, which stays unresolvable).
     */
    private static int[] swizzleIndices(String suffix) {
        if (suffix.isEmpty() || suffix.length() > 4 || suffix.indexOf('.') >= 0) {
            return null;
        }
        int[] indices = new int[suffix.length()];
        for (int i = 0; i < suffix.length(); i++) {
            switch (suffix.charAt(i)) {
                case 'x': case 'r': case 's':
                    indices[i] = 0;
                    break;
                case 'y': case 'g': case 't':
                    indices[i] = 1;
                    break;
                case 'z': case 'b': case 'p':
                    indices[i] = 2;
                    break;
                case 'w': case 'a': case 'q':
                    indices[i] = 3;
                    break;
                default:
                    return null;
            }
        }
        return indices;
    }

    private String parseIdentifier() {
        int start = pos;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                pos++;
            } else {
                break;
            }
        }
        return source.substring(start, pos);
    }

    private void skipWhitespace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        skipWhitespace();
        return pos < source.length() ? source.charAt(pos) : '\0';
    }

    private boolean consume(char expected) {
        skipWhitespace();
        if (pos < source.length() && source.charAt(pos) == expected) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean consumeSequence(String expected) {
        skipWhitespace();
        if (source.regionMatches(pos, expected, 0, expected.length())) {
            pos += expected.length();
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw new ParseException("Expected '" + expected + "' at " + pos + " in: " + source);
        }
    }
}
