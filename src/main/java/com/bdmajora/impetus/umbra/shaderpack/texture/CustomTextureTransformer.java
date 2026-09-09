package com.bdmajora.impetus.umbra.shaderpack.texture;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The string-level counterpart of Umbra's {@code TextureTransformer}: renames a sampler identifier to the raw custom
 * texture's minted name, but only in programs that declare it with a sampler type matching the directive's declared
 * texture target.
 * <p>
 * Umbra does this over a parsed AST; here the declaration is found with a regex over a comment-stripped copy of the
 * source, and the rename is applied to the original text. That covers every form packs actually write —
 * {@code uniform sampler3D colortex6;} and comma lists like {@code uniform sampler2D colortex0, colortex1;} — and
 * silently declines to rename anything it cannot recognise, which is the safe direction: the sampler then keeps its
 * standard render-target unit, exactly as if the directive were absent.
 * <p>
 * The active pack's patch list is installed by the pipeline on load ({@link #setActivePatches}) because the gbuffers,
 * terrain and shadow compile paths reach the transform from static contexts that have no pack handle.
 */
public final class CustomTextureTransformer {

    /** {@code uniform <type> <name>[, <name>...];} — the declaration form every pack uses for samplers. */
    private static final Pattern UNIFORM_DECLARATION =
            Pattern.compile("(?m)^[\\t ]*uniform[\\t ]+(\\w+)[\\t ]+([^;{}()]+);");

    private static volatile List<CustomTexturePatch> activePatches = Collections.emptyList();

    private CustomTextureTransformer() {
    }

    /** Installs the loaded pack's raw-custom-texture patches; pass an empty list when no pack is active. */
    public static void setActivePatches(List<CustomTexturePatch> patches) {
        activePatches = patches == null ? Collections.<CustomTexturePatch>emptyList() : new ArrayList<>(patches);
    }

    public static List<CustomTexturePatch> getActivePatches() {
        return activePatches;
    }

    /** Applies every active patch for {@code stage} to one GLSL stage source. */
    public static String transform(String programName, String source, TextureStage stage) {
        return transform(programName, source, stage, activePatches);
    }

    public static String transform(String programName, String source, TextureStage stage,
                                   List<CustomTexturePatch> patches) {
        if (source == null || patches.isEmpty()) {
            return source;
        }

        String declarationScan = null;
        String result = source;
        for (CustomTexturePatch patch : patches) {
            if (patch.getStage() != stage) {
                continue;
            }
            if (declarationScan == null) {
                declarationScan = stripComments(result);
            }
            String declaredType = findSamplerDeclarationType(declarationScan, patch.getSamplerName());
            if (declaredType == null || !typeMatches(patch.getTextureType(), declaredType)) {
                continue;
            }
            result = renameIdentifier(result, patch.getSamplerName(), patch.getNewSamplerName());
            // The rename changes the text the next patch scans (a program can carry several patched samplers).
            declarationScan = stripComments(result);
        }
        return result;
    }

    /**
     * @return the GLSL type {@code samplerName} is declared as in this source, or null if it is not declared as a
     * uniform here (in which case the program does not use the directive's sampler at all).
     */
    private static String findSamplerDeclarationType(String source, String samplerName) {
        Matcher matcher = UNIFORM_DECLARATION.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(1);
            if (!type.toLowerCase(Locale.ROOT).contains("sampler")) {
                continue;
            }
            for (String declared : matcher.group(2).split(",")) {
                // Drop any array suffix / initializer tail so "colortex6[2]" still matches "colortex6".
                String name = declared.trim();
                int bracket = name.indexOf('[');
                if (bracket >= 0) {
                    name = name.substring(0, bracket).trim();
                }
                if (name.equals(samplerName)) {
                    return type;
                }
            }
        }
        return null;
    }

    /** Umbra {@code TextureTransformer.isTypeValid}: the directive's target vs. the declared sampler type. */
    private static boolean typeMatches(String textureType, String declaredType) {
        Set<String> accepted = acceptedSamplerTypes(textureType);
        return accepted.contains(declaredType.toLowerCase(Locale.ROOT));
    }

    private static Set<String> acceptedSamplerTypes(String textureType) {
        String suffix;
        if ("TEXTURE_1D".equalsIgnoreCase(textureType)) {
            suffix = "sampler1d";
        } else if ("TEXTURE_2D".equalsIgnoreCase(textureType)) {
            suffix = "sampler2d";
        } else if ("TEXTURE_3D".equalsIgnoreCase(textureType)) {
            suffix = "sampler3d";
        } else if ("TEXTURE_RECTANGLE".equalsIgnoreCase(textureType)) {
            suffix = "sampler2drect";
        } else {
            return Collections.emptySet();
        }
        Set<String> accepted = new HashSet<>();
        accepted.add(suffix);
        accepted.add("i" + suffix);
        accepted.add("u" + suffix);
        return accepted;
    }

    private static String renameIdentifier(String source, String from, String to) {
        return source.replaceAll("\\b" + Pattern.quote(from) + "\\b", Matcher.quoteReplacement(to));
    }

    /** Blanks comments so a commented-out declaration cannot trigger a rename. Length is not preserved. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "");
    }
}
