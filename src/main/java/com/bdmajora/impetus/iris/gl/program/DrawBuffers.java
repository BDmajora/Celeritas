package com.bdmajora.impetus.iris.gl.program;

import com.bdmajora.impetus.iris.targets.IrisRenderTargets;

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
 * {@code DRAWBUFFERS} uses one decimal digit per attachment ({@code 0-9}). {@code RENDERTARGETS} uses a
 * comma-separated decimal list and is the path for targets 10-15. When neither is present the program is assumed to
 * write only {@code colortex0}.
 */
public final class DrawBuffers {
    private static final Pattern DRAWBUFFERS = Pattern.compile("/\\*\\s*DRAWBUFFERS:([0-9]+)\\s*\\*/");
    private static final Pattern RENDERTARGETS = Pattern.compile("/\\*\\s*RENDERTARGETS:\\s*([0-9,\\s]+)\\*/");
    private static final Pattern FRAG_COLOR = Pattern.compile("\\bgl_FragColor\\b");
    private static final Pattern NAMED_FRAGMENT_OUTPUT = Pattern.compile(
            "(?m)^([\\t ]*)(?:layout\\s*\\((?s:.*?)\\)\\s*)?"
                    + "(?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*"
                    + "out\\s+(?:(?:lowp|mediump|highp)\\s+)?(float|vec2|vec3|vec4)\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*;\\s*(?://.*)?$");
    private static final Pattern DEFINE_DIRECTIVE = Pattern.compile(
            "^(\\s*#\\s*define\\s+)([A-Za-z_][A-Za-z0-9_]*)(\\b.*)$");
    private static final Pattern LAYOUT_LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)\\b");

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
        return parseActive(fragmentSource, com.bdmajora.impetus.iris.gl.shader.ShaderMacros.standard());
    }

    public static int[] parseActive(String fragmentSource, Map<String, String> defines) {
        if (fragmentSource == null) {
            return DEFAULT.clone();
        }
        int[] raw = parseLastDirective(fragmentSource);
        try {
            String evaluated = com.bdmajora.impetus.iris.shaderpack.preprocessor.PropertiesPreprocessor.preprocess(
                    fragmentSource, defines == null
                            ? com.bdmajora.impetus.iris.gl.shader.ShaderMacros.standard()
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
     * not "leave holes until location 8". {@code gl_FragData[N]} and {@code layout(location = N)} already name those
     * dense output slots, so this only normalizes {@code gl_FragColor} and implicit named outputs.
     */
    public static String rewriteFragmentOutputs(String fragmentSource, int[] drawBuffers) {
        if (fragmentSource == null) {
            return fragmentSource;
        }
        String rewritten = rewriteFragColor(fragmentSource);
        return rewriteNamedFragmentOutputs(rewritten);
    }

    private static String rewriteFragColor(String source) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher define = DEFINE_DIRECTIVE.matcher(lines[i]);
            if (define.matches()) {
                if (!"gl_FragColor".equals(define.group(2))) {
                    lines[i] = define.group(1) + define.group(2)
                            + FRAG_COLOR.matcher(define.group(3))
                            .replaceAll(Matcher.quoteReplacement("gl_FragData[0]"));
                }
                continue;
            }
            if (lines[i].trim().startsWith("#")) {
                continue;
            }
            lines[i] = FRAG_COLOR.matcher(lines[i]).replaceAll(Matcher.quoteReplacement("gl_FragData[0]"));
        }
        return String.join("\n", lines);
    }

    /**
     * Named fragment outputs are kept as real {@code out} declarations, exactly like Iris: an explicit
     * {@code layout(location = N)} already names the dense output slot (the Nth entry of the RENDERTARGETS list),
     * so it passes through untouched; declarations without a layout get {@code layout(location = <declaration
     * order>)} added in place. The old approach — replacing the declaration with
     * {@code #define <name> gl_FragData[slot]} — corrupted any shader that reuses the output's name as a local
     * variable or function parameter (photon's {@code result}/{@code fragment_color}), since the macro rewrites
     * every occurrence.
     */
    private static String rewriteNamedFragmentOutputs(String source) {
        Matcher matcher = NAMED_FRAGMENT_OUTPUT.matcher(source);
        StringBuffer rewritten = new StringBuffer(source.length());
        int implicitSlot = 0;
        while (matcher.find()) {
            String declaration = matcher.group(0);
            Matcher layout = LAYOUT_LOCATION.matcher(declaration);
            if (layout.find()) {
                implicitSlot = Math.max(implicitSlot, Integer.parseInt(layout.group(1)) + 1);
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(declaration));
                continue;
            }
            int outputSlot = implicitSlot++;
            String outputName = matcher.group(3);
            if (outputName.startsWith("iris_") || outputSlot >= IrisRenderTargets.MAX_COLOR_BUFFERS) {
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(declaration));
                continue;
            }
            String indent = matcher.group(1);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(
                    indent + "layout(location = " + outputSlot + ") " + declaration.substring(indent.length())));
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
                buffers[i] = Character.digit(digits.charAt(i), 10);
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
