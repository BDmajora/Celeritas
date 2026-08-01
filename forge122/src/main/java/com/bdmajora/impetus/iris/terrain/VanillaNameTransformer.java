package com.bdmajora.impetus.iris.terrain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the modern-Minecraft (1.17+ core profile) vertex-attribute and matrix names that shader packs use into the
 * GLSL 120 fixed-function built-ins the 1.12.2 context actually provides — the string-level equivalent of Iris's
 * {@code VanillaCoreTransformer} / {@code CompositeCoreTransformer}.
 * <p>
 * Packs ship these because Iris supplies them on every version, so a pack authored once runs everywhere. Two distinct
 * failure modes appear without this pass, both observed on the packs in use:
 * <ul>
 * <li>Complementary's {@code gbuffers_line} references {@code vaPosition}/{@code vaNormal}/{@code modelViewMatrix}
 * <em>without declaring them</em> and fails to compile outright ("undefined variable").</li>
 * <li>Photon's {@code gbuffers_line} <em>does</em> declare them, so it compiles — but nothing ever feeds those
 * attributes on the 1.12.2 draw path, so they silently read the generic default {@code (0,0,0,1)}.</li>
 * </ul>
 * Both are fixed the same way: drop the declarations, then point the references at the fixed-function equivalents.
 */
public final class VanillaNameTransformer {
    /** Modern name → GLSL 120 fixed-function replacement expression. */
    private static final Map<String, String> REPLACEMENTS = new LinkedHashMap<>();

    static {
        // Vertex attributes. 1.12.2 submits these through the fixed-function arrays.
        REPLACEMENTS.put("vaPosition", "gl_Vertex.xyz");
        REPLACEMENTS.put("vaNormal", "gl_Normal");
        REPLACEMENTS.put("vaColor", "gl_Color");
        REPLACEMENTS.put("vaUV0", "gl_MultiTexCoord0.xy");
        // vaUV1 is the 1.17+ overlay coord (damage/hurt flash). There is no fixed-function equivalent; Iris's
        // "no overlay" sentinel is (0, 10), which indexes the transparent corner of the overlay texture.
        REPLACEMENTS.put("vaUV1", "@(0.0, 10.0)");
        // vaUV2 is the 1.17+ lightmap coord in [0, 240] — exactly the range 1.12.2 puts in texcoord 1.
        REPLACEMENTS.put("vaUV2", "@(gl_MultiTexCoord1.xy)");

        // Matrices. The fixed-function stack holds the same values the modern uniforms would.
        REPLACEMENTS.put("modelViewMatrixInverse", "gl_ModelViewMatrixInverse");
        REPLACEMENTS.put("modelViewMatrix", "gl_ModelViewMatrix");
        REPLACEMENTS.put("projectionMatrixInverse", "gl_ProjectionMatrixInverse");
        REPLACEMENTS.put("projectionMatrix", "gl_ProjectionMatrix");
        REPLACEMENTS.put("normalMatrix", "gl_NormalMatrix");
        REPLACEMENTS.put("textureMatrix", "gl_TextureMatrix[0]");

        // Sodium's per-draw chunk translation. The immediate-mode gbuffer paths submit world-space vertices
        // already, so there is no offset to apply; the terrain path has its own handling.
        REPLACEMENTS.put("chunkOffset", "vec3(0.0)");
    }

    /**
     * A declaration of one of the modern names as an attribute, varying input or uniform. Matched on a whole line so
     * the qualifier list ({@code flat in ivec2 vaUV2;}) and any trailing comment are removed with it.
     */
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*(?:flat[\\t ]+|smooth[\\t ]+|noperspective[\\t ]+|centroid[\\t ]+)*"
                    + "(?:attribute|in|uniform)[\\t ]+(\\w+)[\\t ]+(" + String.join("|", REPLACEMENTS.keySet())
                    + ")[\\t ]*;[^\\n]*$");

    /**
     * The two-component attributes whose replacement has to match the type the pack declared. Iris declares
     * {@code iris_UV1}/{@code iris_UV2} as {@code vec2}, but only when the shader does not declare them itself —
     * Photon declares {@code in ivec2 vaUV2;}. Substituting the wrong one turns a working shader into a type error
     * ({@code ivec2 * float} on one side, {@code vec2} where an integer index is wanted on the other), so the
     * declared type is read out of the source before the declaration is stripped.
     */
    private static final Pattern TYPED_PLACEHOLDER = Pattern.compile("@\\(");

    private VanillaNameTransformer() {
    }

    /** @return true if the source mentions any modern name at all (cheap gate before the rewrite). */
    public static boolean isModernNamed(String source) {
        if (source == null) {
            return false;
        }
        for (String name : REPLACEMENTS.keySet()) {
            if (source.contains(name)) {
                return true;
            }
        }
        return false;
    }

    public static String transform(String source) {
        if (!isModernNamed(source)) {
            return source;
        }

        // Read the declared types before stripping, so the two-component replacements can match them.
        Map<String, String> declaredTypes = new LinkedHashMap<>();
        Matcher declarations = DECLARATION.matcher(source);
        while (declarations.find()) {
            declaredTypes.put(declarations.group(2), declarations.group(1));
        }

        // Strip the declarations. A pack that declares `in vec3 vaPosition;` would otherwise end up with a dangling
        // attribute that nothing feeds, and the reference rewrite below would not be able to help it.
        String result = DECLARATION.matcher(source).replaceAll("");

        for (Map.Entry<String, String> entry : REPLACEMENTS.entrySet()) {
            // \b alone would also rewrite a longer identifier that merely ends with the name (e.g. a pack's own
            // `myProjectionMatrix`), so require a non-identifier character (or start/end of input) on both sides.
            String replacement = entry.getValue();
            if (TYPED_PLACEHOLDER.matcher(replacement).find()) {
                // "ivec2" when the pack declared one, "vec2" otherwise (Iris's own fallback type).
                String declared = declaredTypes.get(entry.getKey());
                String type = "ivec2".equals(declared) ? "ivec2" : "vec2";
                replacement = replacement.replace("@(", type + "(");
            }
            result = result.replaceAll("(?<![A-Za-z0-9_])" + Pattern.quote(entry.getKey()) + "(?![A-Za-z0-9_])",
                    Matcher.quoteReplacement(replacement));
        }
        return result;
    }
}
