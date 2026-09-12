package com.bdmajora.impetus.umbra.terrain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Rewrites the 1.17+ attribute and matrix names packs ship (vaPosition, modelViewMatrix) into 1.12.2's fixed-function built-ins; Complementary fails to compile without it, Photon compiles but reads (0,0,0,1)
public final class VanillaNameTransformer {
    // Modern name -> the GLSL 120 fixed-function expression replacing it; ordered so a longer name is tried before a shorter one it contains
    private static final Map<String, String> REPLACEMENTS = new LinkedHashMap<>();

    static {
        // Vertex attributes. 1.12.2 submits these through the fixed-function arrays.
        REPLACEMENTS.put("vaPosition", "gl_Vertex.xyz");
        REPLACEMENTS.put("vaNormal", "gl_Normal");
        REPLACEMENTS.put("vaColor", "gl_Color");
        REPLACEMENTS.put("vaUV0", "gl_MultiTexCoord0.xy");
        // vaUV1 is the 1.17+ overlay coord (damage/hurt flash) with no fixed-function equivalent; Umbra's "no overlay" sentinel (0, 10) indexes the transparent corner of the overlay texture
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

        // Sodium's per-draw chunk translation; the immediate-mode gbuffer paths submit world-space vertices already, and the terrain path has its own handling
        REPLACEMENTS.put("chunkOffset", "vec3(0.0)");
    }

    // A whole-line declaration of one or more names as attribute, varying input or uniform, capturing the declarator list whole since Clarity writes `uniform mat4 modelViewMatrix, projectionMatrix;` and a single-declarator match left it to become a reserved gl_ declaration (C7528, both sky programs down); non-modern declarators are kept
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^([\\t ]*(?:flat[\\t ]+|smooth[\\t ]+|noperspective[\\t ]+|centroid[\\t ]+)*"
                    + "(?:attribute|in|uniform)[\\t ]+)(\\w+)([\\t ]+)"
                    + "([A-Za-z_]\\w*(?:[\\t ]*,[\\t ]*[A-Za-z_]\\w*)*)[\\t ]*;[^\\n]*$");

    // Marks the two-component attributes whose replacement must match the pack's declared type: Iris declares iris_UV1/UV2 as vec2 unless the shader declares them, and Photon declares `in ivec2 vaUV2;`, so the type is read BEFORE the declaration is stripped and filled in here
    private static final Pattern TYPED_PLACEHOLDER = Pattern.compile("@\\(");

    // The GLSL type keywords a variable declaration can start with, used to tell a declaration from a use
    private static final String GLSL_TYPE =
            "(?:bool|int|uint|float|double|[bidu]?vec[234]|d?mat[234](?:x[234])?"
                    + "|[iu]?sampler[123]D(?:Array|Rect)?(?:Shadow)?|[iu]?samplerCube(?:Shadow)?)";

    private VanillaNameTransformer() {
    }

    // Whether the source declares that name as its own parameter or local (uniform/attribute declarations are already stripped, so a typed modern name here is the PACK'S OWN); rewriting would put a user declaration in the reserved gl_ namespace (C7528), as Body Camera's `mat4 projectionMatrix` parameter showed on strict drivers. The lookahead (, ) = ; [) separates a declaration from a use
    private static boolean declaresLocally(String source, String name) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + GLSL_TYPE + "\\s+" + Pattern.quote(name) + "\\s*(?=[,)=;\\[])")
                .matcher(source)
                .find();
    }

    // Cheap gate before the real rewrite: true if the source mentions any modern name, so a pure 1.12.2 pack skips the pass
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

    // Drops the modern attribute declarations and points their uses at the fixed-function built-ins
    public static String transform(String source) {
        if (!isModernNamed(source)) {
            return source;
        }

        // Read the declared types before stripping so the two-component replacements match them, and strip in the same pass; an `in vec3 vaPosition;` left behind would be a dangling attribute nothing feeds
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
            // A name the pack declares as its own parameter or local is left alone (see declaresLocally); names that arrived as a uniform/attribute are exempt since their declaration was just stripped and their references must be rewritten. A pack doing both would need scope tracking, and none in the set does
            if (!declaredTypes.containsKey(entry.getKey()) && declaresLocally(result, entry.getKey())) {
                continue;
            }
            // \b alone would also rewrite a longer identifier merely ending with the name (a pack's `myProjectionMatrix`), so require a non-identifier character or input boundary on both sides
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
