package com.bdmajora.impetus.umbra.terrain;


import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Widens max/min on two integer uniforms to the float overloads, on 120 sources only, since max(int,int) arrived
// in 130 and this port keeps legacy packs on 120. Declaring an int overload instead broke Sildur's and JCL, because
// it captured calls like max(0, someFloat) that 120 then cannot narrow
public final class GlslIntegerOverloadPolyfill {

    // The integer-typed uniforms in the OptiFine/Iris spec
    // A whitelist rather than type inference: only calls whose arguments are built purely from these plus integer
    // literals are rewritten, and everything else keeps GLSL's normal resolution untouched
    private static final Set<String> INTEGER_UNIFORMS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "eyeBrightness", "eyeBrightnessSmooth", "worldTime", "worldDay", "moonPhase", "frameCounter",
            "isEyeInWater", "heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2",
            "entityId", "blockEntityId", "currentRenderedItemId", "currentSelectedBlockId", "fogMode", "fogShape",
            "renderStage", "instanceId", "hideGUI")));

    private static final Pattern VERSION_DIRECTIVE = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)");

    // max(<a>, <b>) and min(<a>, <b>) with no nested parentheses or commas in either argument — a nested call
    // cannot be matched by a regex and is left alone rather than mangled
    private static final Pattern SIMPLE_TWO_ARG_CALL = Pattern.compile(
            "(?<![A-Za-z0-9_])(max|min)\\s*\\(\\s*([^(),]+?)\\s*,\\s*([^(),]+?)\\s*\\)");

    // An integer-typed expression: one integer uniform with an optional swizzle, or one integer literal
    private static final Pattern INTEGER_EXPRESSION = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)(\\.[xyzwrgba]+)?|(\\d+)");

    private GlslIntegerOverloadPolyfill() {
    }

    // Rewrites max/min calls on two integer uniforms to their float overloads, on 120 sources only
    public static String widenIntegerBuiltinCalls(String name, String source) {
        if (source == null || !isLegacyVersion(source)) {
            return source;
        }

        Matcher matcher = SIMPLE_TWO_ARG_CALL.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String first = matcher.group(2);
            String second = matcher.group(3);
            if (isIntegerExpression(first) && isIntegerExpression(second)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(
                        matcher.group(1) + "(float(" + first + "), float(" + second + "))"));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(result);

        return result.toString();
    }

    // True for GLSL 120 or older, and also for a source declaring no #version at all — GLSL defaults to 110 there,
    // which is likewise below 130
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

    // Deliberately conservative: the argument must be exactly one integer uniform reference, optionally swizzled,
    // or one integer literal
    // A float literal, an arithmetic expression or an unknown identifier all decline the rewrite. Getting this
    // wrong would change the type of an expression that compiles fine today, which is a far worse outcome than
    // leaving one broken pack broken
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
