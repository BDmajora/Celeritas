package com.bdmajora.impetus.umbra.gl.blending;

import net.minecraft.client.renderer.GlStateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.umbra.shaderpack.ShaderProperties;
import com.bdmajora.impetus.lwjgl.GL11;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

import java.util.Locale;
import java.util.Optional;

// The alphaTest.<program> directive. Iris compiles it to a discard; on 1.12.2 the alpha test is real GL state
// vanilla sets per render type, so it is overridden while the program is bound and restored after
// Photon sets off everywhere; ignoring that leaves vanilla's threshold culling fragments the pack meant to keep
public final class ProgramAlphaTest {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    private static final int GL_ALPHA_TEST = 0x0BC0;
    private static final int GL_ALPHA_TEST_FUNC = 0x0BC1;
    private static final int GL_ALPHA_TEST_REF = 0x0BC2;

    // The alpha state that was live when apply() ran, captured so restore() puts back exactly that rather than a
    // constant
    // It has to be captured because vanilla sets alphaFunc to different references at different phases — 0.1 for
    // most, 0.5 for the cutout pass — so any single assumed value would be wrong for some phase
    // OptiFine sidesteps this by letting vanilla re-set the state itself; this port restores it explicitly
    private boolean savedEnabled;
    private int savedFunction;
    private float savedReference;
    private boolean saved;

    private static final ProgramAlphaTest EMPTY = new ProgramAlphaTest(false, false, 0, 0.0f);

    private final boolean specified;
    // Set by `alphaTest.<program> = off`, which disables the test outright rather than giving it a function —
    // distinct from ALWAYS, which is a function that happens to pass everything
    private final boolean disabled;
    private final int function;
    private final float reference;

    private ProgramAlphaTest(boolean specified, boolean disabled, int function, float reference) {
        this.specified = specified;
        this.disabled = disabled;
        this.function = function;
        this.reference = reference;
    }

    // No directive; vanilla's alpha test stays
    public static ProgramAlphaTest empty() {
        return EMPTY;
    }

    // Reads alphaTest.<program>
    public static ProgramAlphaTest from(ShaderProperties properties, String programName) {
        Optional<String> value = properties.getAlphaTestOverride(programName);
        if (!value.isPresent()) {
            return EMPTY;
        }

        String raw = value.get().trim();
        if (raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("false")) {
            return new ProgramAlphaTest(true, true, 0, 0.0f);
        }

        String[] parts = raw.split("\\s+");
        Integer function = parseFunction(parts[0]);
        if (function == null) {
            LOGGER.warn("[Umbra] Unknown alpha test function '{}' in alphaTest.{}, ignoring it", parts[0], programName);
            return EMPTY;
        }
        // GL_ALWAYS/GL_NEVER take no reference value; everything else needs one.
        float reference = 0.0f;
        if (parts.length > 1) {
            try {
                reference = Float.parseFloat(parts[1]);
            } catch (NumberFormatException e) {
                LOGGER.warn("[Umbra] Malformed alpha test reference '{}' in alphaTest.{}, ignoring it",
                        parts[1], programName);
                return EMPTY;
            }
        } else if (function != GL11.GL_ALWAYS && function != GL11.GL_NEVER) {
            LOGGER.warn("[Umbra] alphaTest.{} declares '{}' with no reference value, ignoring it", programName, raw);
            return EMPTY;
        }

        return new ProgramAlphaTest(true, function == GL11.GL_ALWAYS, function, reference);
    }

    // GREATER, GEQUAL and the rest to GL constants; null when unknown
    private static Integer parseFunction(String name) {
        switch (name.toUpperCase(Locale.ROOT)) {
            case "NEVER":
                return GL11.GL_NEVER;
            case "LESS":
                return GL11.GL_LESS;
            case "EQUAL":
                return GL11.GL_EQUAL;
            case "LEQUAL":
                return GL11.GL_LEQUAL;
            case "GREATER":
                return GL11.GL_GREATER;
            case "NOTEQUAL":
                return GL11.GL_NOTEQUAL;
            case "GEQUAL":
                return GL11.GL_GEQUAL;
            case "ALWAYS":
                return GL11.GL_ALWAYS;
            default:
                return null;
        }
    }

    // Whether anything needs applying
    public boolean hasDirectives() {
        return this.specified;
    }

    // This alpha test as a GLSL discard, or an empty string when it passes everything
    // Mirrors Iris's AlphaTest.toExpression, including its NEGATED form: `if (!(a > ref)) discard;` rather than
    // `if (a < ref) discard;`
    // The two differ on NaN — the negated form discards a non-finite alpha, the direct comparison keeps it — and
    // Iris's is the stricter and correct one
    // ALWAYS emits nothing at all, which is what leaves an unspecified solid pass carrying no discard; NEVER
    // discards unconditionally
    public String toGlslDiscard(String alphaAccessor, String indent) {
        if (!this.specified || this.disabled || this.function == GL11.GL_ALWAYS) {
            return "";
        }
        if (this.function == GL11.GL_NEVER) {
            return indent + "discard;\n";
        }
        String op = glslOperatorFor(this.function);
        if (op == null) {
            LOGGER.warn("[Umbra] Unsupported alphaTest function 0x{}; treating as always-pass",
                    Integer.toHexString(this.function));
            return "";
        }
        return glslDiscard(alphaAccessor, op, Float.toString(this.reference), indent);
    }

    // The same negated-comparison discard, for callers that supply their own threshold rather than the pack's
    public static String glslDiscard(String alphaAccessor, String operator, String threshold, String indent) {
        return indent + "if (!(" + alphaAccessor + " " + operator + " " + threshold + ")) {\n"
                + indent + "    discard;\n"
                + indent + "}\n";
    }

    // GL comparison enum to its GLSL operator, matching Iris's AlphaTestFunction
    // Null for ALWAYS and NEVER, which have no operator because they do not compare anything
    private static String glslOperatorFor(int function) {
        switch (function) {
            case GL11.GL_LESS: return "<";
            case GL11.GL_EQUAL: return "==";
            case GL11.GL_LEQUAL: return "<=";
            case GL11.GL_GREATER: return ">";
            case GL11.GL_NOTEQUAL: return "!=";
            case GL11.GL_GEQUAL: return ">=";
            default: return null;
        }
    }

    // The pack-declared reference value, uploaded as the alphaTestRef uniform for packs that do their own discard
    public float getReference() {
        return this.disabled ? 0.0f : this.reference;
    }

    // Applies the override, capturing the previous state first so restore() can put it back exactly
    // Goes through GlStateManager rather than raw GL, so vanilla's own state cache stays coherent and its later
    // enable/disable calls are not silently skipped
    public void apply() {
        if (!this.specified) {
            return;
        }
        // Read the live state once so restore() is exact. Only runs for a program that actually declares an
        // override, so the cost of the query is bounded by how many the pack declares.
        this.savedEnabled = LWJGL.glGetBoolean(GL_ALPHA_TEST);
        this.savedFunction = LWJGL.glGetInteger(GL_ALPHA_TEST_FUNC);
        this.savedReference = LWJGL.glGetFloat(GL_ALPHA_TEST_REF);
        this.saved = true;
        if (this.disabled) {
            GlStateManager.disableAlpha();
            return;
        }
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(this.function, this.reference);
    }

    // Puts back whatever alpha state was live before apply(). Must run before the composite chain or vanilla's GUI
    // pass, or the pack's threshold leaks into geometry it was never meant to affect
    public void restore() {
        if (!this.specified || !this.saved) {
            return;
        }
        this.saved = false;
        if (this.savedEnabled) {
            GlStateManager.enableAlpha();
        } else {
            GlStateManager.disableAlpha();
        }
        GlStateManager.alphaFunc(this.savedFunction, this.savedReference);
    }
}
