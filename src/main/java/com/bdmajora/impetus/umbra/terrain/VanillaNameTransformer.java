package com.bdmajora.impetus.umbra.terrain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Rewrites the modern-Minecraft (1.17+ core profile) vertex-attribute and matrix names packs use into the GLSL 120
// fixed-function built-ins a 1.12.2 context actually provides
// The string-level equivalent of Iris's VanillaCoreTransformer and CompositeCoreTransformer
// Packs ship those names because Iris supplies them on every version, so a pack authored once runs everywhere
// Without this pass there are two distinct failures, both seen on packs in real use
//   Complementary's gbuffers_line references vaPosition, vaNormal and modelViewMatrix WITHOUT declaring them, and
//   fails to compile outright with "undefined variable"
//   Photon's gbuffers_line DOES declare them, so it compiles — but nothing on the 1.12.2 draw path ever feeds those
//   attributes, so they silently read the generic default (0,0,0,1) and the geometry collapses
// Both are fixed the same way: drop the declarations, then point the references at the fixed-function equivalents
public final class VanillaNameTransformer {
    // Modern name -> the GLSL 120 fixed-function expression that replaces it
    // Ordered, because a longer name must be tried before a shorter one it contains
    private static final Map<String, String> REPLACEMENTS = new LinkedHashMap<>();

    static {
        // Vertex attributes. 1.12.2 submits these through the fixed-function arrays.
        REPLACEMENTS.put("vaPosition", "gl_Vertex.xyz");
        REPLACEMENTS.put("vaNormal", "gl_Normal");
        REPLACEMENTS.put("vaColor", "gl_Color");
        REPLACEMENTS.put("vaUV0", "gl_MultiTexCoord0.xy");
        // vaUV1 is the 1.17+ overlay coord (damage/hurt flash). There is no fixed-function equivalent; Umbra's
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

    // A declaration of one or more names as an attribute, varying input or uniform
    // Matched on a WHOLE line so the qualifier list (`flat in ivec2 vaUV2;`) and any trailing comment come with it
    // The declarator list is captured as a whole rather than restricted to modern names, because GLSL lets one
    // declaration introduce several — Clarity writes `uniform mat4 modelViewMatrix, projectionMatrix;`
    // Matching only single declarators left that line in place, and the reference rewrite below then turned it into
    // `uniform mat4 gl_ModelViewMatrix, gl_ProjectionMatrix;` — a user declaration in the reserved gl_ namespace,
    // which every driver rejects with C7528, taking both sky programs down with it
    // Declarators that are NOT modern names are kept; only the ones being replaced by a built-in are removed
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^([\\t ]*(?:flat[\\t ]+|smooth[\\t ]+|noperspective[\\t ]+|centroid[\\t ]+)*"
                    + "(?:attribute|in|uniform)[\\t ]+)(\\w+)([\\t ]+)"
                    + "([A-Za-z_]\\w*(?:[\\t ]*,[\\t ]*[A-Za-z_]\\w*)*)[\\t ]*;[^\\n]*$");

    // Marks the two-component attributes whose replacement has to match the type the pack declared
    // Iris declares iris_UV1 and iris_UV2 as vec2, but only when the shader does not declare them itself — and
    // Photon declares `in ivec2 vaUV2;`
    // Substituting the wrong one turns a working shader into a type error either way: ivec2 * float on one side,
    // vec2 where an integer index is wanted on the other
    // So the declared type is read out of the source BEFORE the declaration is stripped, and this placeholder is
    // where it gets filled in
    private static final Pattern TYPED_PLACEHOLDER = Pattern.compile("@\\(");

    // The GLSL type keywords a variable declaration can start with, used to tell a declaration from a use
    private static final String GLSL_TYPE =
            "(?:bool|int|uint|float|double|[bidu]?vec[234]|d?mat[234](?:x[234])?"
                    + "|[iu]?sampler[123]D(?:Array|Rect)?(?:Shadow)?|[iu]?samplerCube(?:Shadow)?)";

    private VanillaNameTransformer() {
    }

    // Whether the source declares that name as its own function parameter or local variable
    // The uniform and attribute declarations are stripped before this is consulted, so a modern name still declared
    // with a type at this point is the PACK'S OWN variable
    // Rewriting it would be wrong twice over: it renames something the pack owns, and because every replacement is
    // a gl_ built-in it also puts a user declaration in the reserved gl_ namespace, which the spec forbids and
    // drivers reject with C7528
    // Same hazard the declarator handling above exists to avoid, reached by another route: Body Camera's
    // composite.fsh writes `vec3 projectAndDivide(mat4 projectionMatrix, vec3 position)`, which this pass turned
    // into a `mat4 gl_ProjectionMatrix` parameter. NVIDIA tolerates it, so it went undetected; a stricter driver
    // drops the whole composite
    // The lookahead is what separates a declaration from a use: a declarator is always followed by , or ) for a
    // parameter, or =, ; or [ for a local
    private static boolean declaresLocally(String source, String name) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + GLSL_TYPE + "\\s+" + Pattern.quote(name) + "\\s*(?=[,)=;\\[])")
                .matcher(source)
                .find();
    }

    // Cheap gate before the real rewrite: true if the source mentions any modern name at all, so a pack written
    // purely against 1.12.2 skips the whole pass
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

        // Read the declared types before stripping, so the two-component replacements can match them, and strip the
        // declarations in the same pass. A pack that declares `in vec3 vaPosition;` would otherwise end up with a
        // dangling attribute that nothing feeds, and the reference rewrite below would not be able to help it.
        Map<String, String> declaredTypes = new LinkedHashMap<>();
        Matcher declarations = DECLARATION.matcher(source);
        StringBuffer stripped = new StringBuffer(source.length());
        while (declarations.find()) {
            String type = declarations.group(2);
            List<String> kept = new ArrayList<>();
            boolean sawModern = false;
            for (String declarator : declarations.group(4).split(",")) {
                String name = declarator.trim();
                if (REPLACEMENTS.containsKey(name)) {
                    declaredTypes.put(name, type);
                    sawModern = true;
                } else {
                    kept.add(name);
                }
            }
            if (!sawModern) {
                declarations.appendReplacement(stripped, Matcher.quoteReplacement(declarations.group()));
            } else {
                // All declarators were modern names: the whole line goes. Otherwise re-emit the survivors.
                declarations.appendReplacement(stripped, kept.isEmpty() ? ""
                        : Matcher.quoteReplacement(declarations.group(1) + type + declarations.group(3)
                                + String.join(", ", kept) + ";"));
            }
        }
        declarations.appendTail(stripped);
        String result = stripped.toString();

        for (Map.Entry<String, String> entry : REPLACEMENTS.entrySet()) {
            // A name the pack declares as its own parameter or local is left entirely alone (see declaresLocally).
            // Names that arrived as a uniform/attribute are exempt from that check: their declaration was just
            // stripped, so their references have to be rewritten or they dangle. A pack that does both — declares the
            // modern uniform *and* reuses the name for a local — would still get the local rewritten; no pack in the
            // set does, and resolving it properly needs scope tracking rather than a regex.
            if (!declaredTypes.containsKey(entry.getKey()) && declaresLocally(result, entry.getKey())) {
                continue;
            }
            // \b alone would also rewrite a longer identifier that merely ends with the name (e.g. a pack's own
            // `myProjectionMatrix`), so require a non-identifier character (or start/end of input) on both sides.
            String replacement = entry.getValue();
            if (TYPED_PLACEHOLDER.matcher(replacement).find()) {
                // "ivec2" when the pack declared one, "vec2" otherwise (Umbra's own fallback type).
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
