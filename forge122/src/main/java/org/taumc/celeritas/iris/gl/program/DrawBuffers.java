package org.taumc.celeritas.iris.gl.program;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the OptiFine {@code /* DRAWBUFFERS:0246 *&#47;} (and the newer Iris {@code /* RENDERTARGETS: 0,2,4,6 *&#47;})
 * directive from a fragment shader, which declares the set of color attachments the program writes to.
 * <p>
 * {@code DRAWBUFFERS} uses one hex digit per attachment ({@code 0-9}, then {@code a-f} for 10-15). {@code RENDERTARGETS}
 * uses a comma-separated decimal list. When neither is present the program is assumed to write only {@code colortex0}.
 */
public final class DrawBuffers {
    private static final Pattern DRAWBUFFERS = Pattern.compile("/\\*\\s*DRAWBUFFERS:([0-9a-fA-F]+)\\s*\\*/");
    private static final Pattern RENDERTARGETS = Pattern.compile("/\\*\\s*RENDERTARGETS:\\s*([0-9,\\s]+)\\*/");

    /** The default when a fragment shader declares no directive: write to colortex0 only. */
    public static final int[] DEFAULT = new int[]{0};

    private DrawBuffers() {
    }

    /**
     * Parses the directive from the source with its preprocessor conditionals EVALUATED first (using the standard
     * macro environment; pack option values are already baked into the source by the option system). Iris parity:
     * it extracts directives from the JCPP-preprocessed source, so option-gated variants (Complementary's deferred1
     * declares five DRAWBUFFERS/RENDERTARGETS variants across its colored-lighting gates) resolve to the ACTIVE one.
     * Raw-source {@link #parse} takes the first textual match, which desynchronizes the ping-pong flip accounting
     * from what the GPU actually writes whenever the active variant is not the first.
     */
    public static int[] parseActive(String fragmentSource) {
        if (fragmentSource == null) {
            return DEFAULT.clone();
        }
        int[] raw = parse(fragmentSource);
        try {
            String evaluated = org.taumc.celeritas.iris.shaderpack.preprocessor.PropertiesPreprocessor.preprocess(
                    fragmentSource, org.taumc.celeritas.iris.gl.shader.ShaderMacros.standard());
            int[] active = parse(evaluated);
            // FAIL-SAFE: the conditional evaluator cannot expand chained/function-like macros, so a wrongly-dead
            // branch can swallow the only directive. Trust the evaluated result only when it actually found one;
            // a source whose directives all disappeared falls back to the raw first-match (previous behavior).
            boolean evaluatedFound = !java.util.Arrays.equals(active, DEFAULT)
                    || (evaluated.contains("DRAWBUFFERS:0") || evaluated.contains("RENDERTARGETS: 0"));
            return evaluatedFound ? active : raw;
        } catch (RuntimeException e) {
            return raw;
        }
    }

    public static int[] parse(String fragmentSource) {
        if (fragmentSource == null) {
            return DEFAULT.clone();
        }

        Matcher rt = RENDERTARGETS.matcher(fragmentSource);
        if (rt.find()) {
            String[] parts = rt.group(1).trim().split("\\s*,\\s*");
            int[] buffers = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                buffers[i] = Integer.parseInt(parts[i].trim());
            }
            return buffers;
        }

        Matcher db = DRAWBUFFERS.matcher(fragmentSource);
        if (db.find()) {
            String digits = db.group(1);
            int[] buffers = new int[digits.length()];
            for (int i = 0; i < digits.length(); i++) {
                buffers[i] = Character.digit(digits.charAt(i), 16);
            }
            return buffers;
        }

        return DEFAULT.clone();
    }
}
