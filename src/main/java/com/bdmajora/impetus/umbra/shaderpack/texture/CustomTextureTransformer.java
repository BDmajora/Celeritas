package com.bdmajora.impetus.umbra.shaderpack.texture;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// String-level counterpart of Iris's TextureTransformer: renames a sampler to a custom texture's minted name,
// but only where its declared type matches. Declines anything it cannot recognise, which leaves the standard unit
// The patch list is installed statically since the compile paths have no pack handle to thread through
public final class CustomTextureTransformer {

    // uniform <type> <name>[, <name>...]; — the declaration form every pack uses for samplers, including the
    // comma-list variant
    private static final Pattern UNIFORM_DECLARATION =
            Pattern.compile("(?m)^[\\t ]*uniform[\\t ]+(\\w+)[\\t ]+([^;{}()]+);");

    private static volatile List<CustomTexturePatch> activePatches = Collections.emptyList();

    private CustomTextureTransformer() {
    }

    // Installs the loaded pack's raw-custom-texture patches. An empty list when no pack is active, which makes
    // transform below a pass-through
    public static void setActivePatches(List<CustomTexturePatch> patches) {
        activePatches = patches == null ? Collections.<CustomTexturePatch>emptyList() : new ArrayList<>(patches);
    }

    // Patches for the active pack
    public static List<CustomTexturePatch> getActivePatches() {
        return activePatches;
    }

    // Applies every active patch belonging to that stage to one GLSL stage source
    // Filtered by stage because a directive is scoped to one — the same sampler name legitimately means different
    // things in gbuffers and in deferred
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

    // The GLSL type that sampler is declared as in this source, or null when it is not declared here at all —
    // which means this program simply does not use the directive's sampler and needs no rename
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

    // The type check itself, matching Iris's TextureTransformer.isTypeValid: the directive's declared target
    // against the sampler type the program declared. This is the whole reason the transform is per-program
    private static boolean typeMatches(String textureType, String declaredType) {
        Set<String> accepted = acceptedSamplerTypes(textureType);
        return accepted.contains(declaredType.toLowerCase(Locale.ROOT));
    }

    // GLSL sampler types valid for a texture type
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

    // Whole-word rename
    private static String renameIdentifier(String source, String from, String to) {
        return source.replaceAll("\\b" + Pattern.quote(from) + "\\b", Matcher.quoteReplacement(to));
    }

    // Blanks comments so a commented-out declaration cannot trigger a rename
    // Length is deliberately NOT preserved, so the stripped copy is only ever used to DETECT a declaration — the
    // rename itself is applied to the original text, where offsets still line up
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "");
    }
}
