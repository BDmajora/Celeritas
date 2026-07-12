package org.taumc.celeritas.iris.gl.program;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
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
    private static final Pattern FRAG_DATA_INDEX =
            Pattern.compile("\\b((?:gl|iris)_FragData)\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern OUTPUT_LAYOUT_LOCATION = Pattern.compile(
            "\\blayout\\s*\\(([^)]*?\\blocation\\s*=\\s*)(\\d+)([^)]*?)\\)"
                    + "(\\s*(?:\\b(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*\\bout\\b)");

    /** The default when a fragment shader declares no directive: write to colortex0 only. */
    public static final int[] DEFAULT = new int[]{0};

    private DrawBuffers() {
    }

    /**
     * Parses the directive from the source with its preprocessor conditionals evaluated first. Iris extracts
     * directives from preprocessed source, so option-gated variants (Complementary's deferred1 declares several
     * DRAWBUFFERS/RENDERTARGETS variants across its colored-lighting gates) resolve to the active one. Raw-source
     * {@link #parse} takes the first textual match, which desynchronizes the ping-pong flip accounting from what
     * the GPU actually writes whenever the active variant is not the first.
     */
    public static int[] parseActive(String fragmentSource) {
        return parseActive(fragmentSource, org.taumc.celeritas.iris.gl.shader.ShaderMacros.standard());
    }

    public static int[] parseActive(String fragmentSource, Map<String, String> defines) {
        if (fragmentSource == null) {
            return DEFAULT.clone();
        }
        int[] raw = parseLastDirective(fragmentSource);
        try {
            String evaluated = org.taumc.celeritas.iris.shaderpack.preprocessor.PropertiesPreprocessor.preprocess(
                    fragmentSource, defines == null
                            ? org.taumc.celeritas.iris.gl.shader.ShaderMacros.standard()
                            : defines);
            List<Directive> activeDirectives = directives(evaluated);
            // The conditional evaluator cannot expand every shader-pack macro shape. If evaluation removes every
            // target directive, keep the source-order fallback instead of inventing a default target mask.
            return activeDirectives.isEmpty()
                    ? raw
                    : activeDirectives.get(activeDirectives.size() - 1).buffers.clone();
        } catch (RuntimeException e) {
            return raw;
        }
    }

    public static int[] parse(String fragmentSource) {
        if (fragmentSource == null) {
            return DEFAULT.clone();
        }

        List<Directive> directives = directives(fragmentSource);
        return directives.isEmpty() ? DEFAULT.clone() : directives.get(0).buffers.clone();
    }

    public static int[] sanitize(int[] drawBuffers, int maxExclusive) {
        return sanitize(drawBuffers, maxExclusive, null);
    }

    public static int[] sanitize(int[] drawBuffers, int maxExclusive, IntConsumer invalidBufferConsumer) {
        if (drawBuffers == null || drawBuffers.length == 0) {
            return DEFAULT.clone();
        }
        List<Integer> valid = new ArrayList<>();
        boolean[] seen = new boolean[Math.max(0, maxExclusive)];
        for (int buffer : drawBuffers) {
            if (buffer >= 0 && buffer < maxExclusive) {
                if (!seen[buffer]) {
                    valid.add(buffer);
                    seen[buffer] = true;
                }
            } else if (invalidBufferConsumer != null) {
                invalidBufferConsumer.accept(buffer);
            }
        }
        if (valid.isEmpty()) {
            return DEFAULT.clone();
        }
        int[] result = new int[valid.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = valid.get(i);
        }
        return result;
    }

    /**
     * Iris packs shader-pack render targets into dense framebuffer color attachments before drawing. A directive such as
     * {@code DRAWBUFFERS:03648} therefore means "shader output slot 0 writes colortex0, slot 1 writes colortex3, ...",
     * not "leave holes until location 8". Rewrite explicit logical target writes into the dense slots the framebuffer
     * enables. Dense slot writes are preserved; only explicit high logical target writes are folded back into the
     * enabled slot list, so sparse target packs cannot write outside the generated output array or disabled buffers.
     */
    public static String rewriteFragmentOutputs(String fragmentSource, int[] drawBuffers) {
        if (fragmentSource == null || drawBuffers == null || drawBuffers.length == 0) {
            return fragmentSource;
        }
        String rewritten = rewriteFragDataIndices(fragmentSource, drawBuffers);
        return rewriteLayoutLocations(rewritten, drawBuffers);
    }

    public static int outputSlotForTarget(int[] drawBuffers, int renderTarget) {
        if (drawBuffers == null) {
            return -1;
        }
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            if (drawBuffers[slot] == renderTarget) {
                return slot;
            }
        }
        return -1;
    }

    private static String rewriteFragDataIndices(String source, int[] drawBuffers) {
        Matcher matcher = FRAG_DATA_INDEX.matcher(source);
        StringBuffer rewritten = new StringBuffer(source.length());
        while (matcher.find()) {
            int renderTarget = Integer.parseInt(matcher.group(2));
            int outputSlot = outputSlotForTarget(drawBuffers, renderTarget);
            if (outputSlot >= 0 && renderTarget >= drawBuffers.length) {
                matcher.appendReplacement(rewritten,
                        Matcher.quoteReplacement(matcher.group(1) + "[" + outputSlot + "]"));
            } else {
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String rewriteLayoutLocations(String source, int[] drawBuffers) {
        Matcher matcher = OUTPUT_LAYOUT_LOCATION.matcher(source);
        StringBuffer rewritten = new StringBuffer(source.length());
        while (matcher.find()) {
            int renderTarget = Integer.parseInt(matcher.group(2));
            int outputSlot = outputSlotForTarget(drawBuffers, renderTarget);
            if (outputSlot >= 0 && renderTarget >= drawBuffers.length) {
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(
                        "layout(" + matcher.group(1) + outputSlot + matcher.group(3) + ")" + matcher.group(4)));
            } else {
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    /**
     * Preprocessed shader source can legitimately still contain multiple target directives when the pack layers
     * nested option gates (Complementary's water/lava path does this for colored lighting and reflections). Iris
     * extracts directives from preprocessed source order, so use the last surviving directive instead of the first
     * fallback directive that appears before the active optional writes.
     */
    private static int[] parseLastDirective(String fragmentSource) {
        List<Directive> directives = directives(fragmentSource);
        if (directives.isEmpty()) {
            return DEFAULT.clone();
        }
        return directives.get(directives.size() - 1).buffers.clone();
    }

    private static List<Directive> directives(String fragmentSource) {
        List<Directive> directives = new ArrayList<>();

        Matcher rt = RENDERTARGETS.matcher(fragmentSource);
        while (rt.find()) {
            int[] buffers = parseRenderTargets(rt.group(1));
            if (buffers.length == 0) {
                continue;
            }
            directives.add(new Directive(rt.start(), buffers));
        }

        Matcher db = DRAWBUFFERS.matcher(fragmentSource);
        while (db.find()) {
            String digits = db.group(1);
            int[] buffers = new int[digits.length()];
            for (int i = 0; i < digits.length(); i++) {
                buffers[i] = Character.digit(digits.charAt(i), 16);
            }
            directives.add(new Directive(db.start(), buffers));
        }

        directives.sort(Comparator.comparingInt(directive -> directive.offset));
        return directives;
    }

    private static int[] parseRenderTargets(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return new int[0];
        }
        String[] parts = trimmed.split("\\s*,\\s*");
        int[] buffers = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                buffers[count++] = Integer.parseInt(part.trim());
            } catch (NumberFormatException ignored) {
                return new int[0];
            }
        }
        if (count == buffers.length) {
            return buffers;
        }
        int[] compact = new int[count];
        System.arraycopy(buffers, 0, compact, 0, count);
        return compact;
    }

    private static final class Directive {
        private final int offset;
        private final int[] buffers;

        private Directive(int offset, int[] buffers) {
            this.offset = offset;
            this.buffers = buffers;
        }
    }
}
