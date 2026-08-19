package com.bdmajora.impetus.iris.terrain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Widens {@code max}/{@code min} calls whose arguments are both integer-typed uniforms to their float overloads, for
 * {@code #version 120} sources. GLSL only gained {@code max(int, int)} in 130, so packs that use it are relying on
 * Iris — which never hits the problem because {@code TransformPatcher} bumps every shader to at least
 * {@code #version 330}. This port keeps legacy packs on 120 on purpose, so the call has to be adapted instead.
 * <p>
 * Just Colored Lighting's {@code gbuffers_skybasic} does
 * {@code float(max(eyeBrightnessSmooth.y, eyeBrightness.y))/240.}, which the driver rejects with "ambiguous
 * overloaded function reference".
 * <p>
 * <b>Declaring an {@code int max(int, int)} overload instead does not work</b> and was tried: adding it to the
 * overload set makes previously-fine calls like {@code max(0, someFloat)} resolve to the integer candidate, and GLSL
 * 120 forbids the implicit float→int narrowing that follows. Sildur's and JCL both lost programs that way. Rewriting
 * the specific call sites keeps every other call's resolution exactly as it was.
 */
public final class GlslIntegerOverloadPolyfill {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    /**
     * The integer-typed uniforms in the OptiFine/Iris spec. Only calls whose arguments are built purely out of these
     * (plus integer literals) are rewritten — anything else keeps GLSL's normal resolution.
     */
    private static final Set<String> INTEGER_UNIFORMS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "eyeBrightness", "eyeBrightnessSmooth", "worldTime", "worldDay", "moonPhase", "frameCounter",
            "isEyeInWater", "heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2",
            "entityId", "blockEntityId", "currentRenderedItemId", "currentSelectedBlockId", "fogMode", "fogShape",
            "renderStage", "instanceId", "hideGUI")));

    private static final Pattern VERSION_DIRECTIVE = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)");

    /** {@code max(<a>, <b>)} / {@code min(<a>, <b>)} with no nested parentheses or commas in either argument. */
    private static final Pattern SIMPLE_TWO_ARG_CALL = Pattern.compile(
            "(?<![A-Za-z0-9_])(max|min)\\s*\\(\\s*([^(),]+?)\\s*,\\s*([^(),]+?)\\s*\\)");

    /** An integer-typed expression: an integer uniform with an optional swizzle, or an integer literal. */
    private static final Pattern INTEGER_EXPRESSION = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)(\\.[xyzwrgba]+)?|(\\d+)");

    private GlslIntegerOverloadPolyfill() {
    }

    public static String widenIntegerBuiltinCalls(String name, String source) {
        if (source == null || !isLegacyVersion(source)) {
            return source;
        }

        Matcher matcher = SIMPLE_TWO_ARG_CALL.matcher(source);
        StringBuffer result = new StringBuffer();
        int rewritten = 0;
        while (matcher.find()) {
            String first = matcher.group(2);
            String second = matcher.group(3);
            if (isIntegerExpression(first) && isIntegerExpression(second)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(
                        matcher.group(1) + "(float(" + first + "), float(" + second + "))"));
                rewritten++;
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(result);

        if (rewritten > 0) {
            LOGGER.info("[Iris] Program '{}': widened {} integer max/min call(s) to float (GLSL 120 has no integer overload)",
                    name, rewritten);
        }
        return result.toString();
    }

    /** True when the source is GLSL 120 or older (or declares no version, which also defaults below 130). */
    private static boolean isLegacyVersion(String source) {
        Matcher matcher = VERSION_DIRECTIVE.matcher(source);
        if (!matcher.find()) {
            return true;
        }
        try {
            return Integer.parseInt(matcher.group(1)) < 130;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Conservative: the argument must be exactly one integer uniform reference (optionally swizzled) or one integer
     * literal. A float literal, an arithmetic expression or an unknown identifier all decline the rewrite, because
     * getting this wrong would change the type of an expression that compiles fine today.
     */
    private static boolean isIntegerExpression(String expression) {
        String trimmed = expression.trim();
        Matcher matcher = INTEGER_EXPRESSION.matcher(trimmed);
        if (!matcher.matches()) {
            return false;
        }
        if (matcher.group(3) != null) {
            return true; // bare integer literal
        }
        return INTEGER_UNIFORMS.contains(matcher.group(1));
    }
}
