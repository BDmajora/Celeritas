package com.bdmajora.impetus.iris.shaderpack.preprocessor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Light-touch GLSL source transformation applied after {@code #include} flattening and before GL compilation.
 * <p>
 * Responsibilities handled here (Phase 1):
 * <ul>
 *     <li>Locating the {@code #version} directive (which GLSL requires to be the first non-comment, non-blank line)
 *         and exposing the declared version.</li>
 *     <li>Injecting {@code #define} macros (shader pack options + pipeline feature flags) immediately after the
 *         {@code #version} line, where they are legal.</li>
 *     <li>Injecting a default {@code #version} when a pack omits one (1.12.2 packs commonly target {@code 120}).</li>
 * </ul>
 * <p>
 * Legacy fixed-function built-in substitution ({@code gl_MultiTexCoord0}, {@code gl_Color}, {@code gl_Normal}, ...)
 * into explicit vertex attributes is intentionally <em>not</em> performed here: it depends on the concrete attribute
 * bindings established by the GL program layer and is implemented in a later phase. {@link #replaceLegacyBuiltins} is
 * provided as the documented seam for that work.
 */
public final class GlslPreprocessor {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    /**
     * The trailing {@code \r?} is load-bearing. Every user of this pattern splits on {@code "\n"} alone and calls
     * {@code matches()}, which must consume the whole line; {@code .} does not match a line terminator and {@code \r}
     * is one, so on a pack shipped with Windows line endings <b>every</b> {@code #version} match failed. That silently
     * broke three separate things at once — {@link #detectVersion} reported "no #version", {@link #injectDefines} lost
     * the anchor it inserts macros after, and {@link #hoistExtensionDirectives} would have hoisted directives to index
     * 0, i.e. above {@code #version}, which is illegal in its own right.
     */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^\\s*#version\\s+(\\d+)(?:\\s+(\\w+))?.*\\r?$");

    /** Default GLSL version assumed for 1.12.2-era packs that omit a {@code #version} directive. */
    public static final int DEFAULT_VERSION = 120;

    private GlslPreprocessor() {
    }

    /**
     * @return the GLSL version number declared by the first {@code #version} directive, or {@code -1} if none.
     */
    public static int detectVersion(List<String> lines) {
        for (String line : lines) {
            Matcher m = VERSION_PATTERN.matcher(line);
            if (m.matches()) {
                return Integer.parseInt(m.group(1));
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            // First meaningful line wasn't #version; GLSL will assume 110 but packs rely on 120 built-ins.
            return -1;
        }
        return -1;
    }

    /**
     * Inserts the given macro definitions immediately after the {@code #version} line (inserting a default
     * {@code #version} first if the source has none), returning the rewritten source lines.
     *
     * @param defines ordered macro name -> value; an empty value yields a bare {@code #define NAME}.
     */
    public static List<String> injectDefines(List<String> lines, Map<String, String> defines) {
        List<String> out = new ArrayList<>(lines.size() + defines.size() + 1);

        int versionIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (VERSION_PATTERN.matcher(lines.get(i)).matches()) {
                versionIndex = i;
                break;
            }
        }

        if (versionIndex < 0) {
            // No #version present: prepend a default one, then defines, then the original source.
            out.add("#version " + DEFAULT_VERSION);
            out.addAll(toDefineLines(defines));
            out.addAll(lines);
            return out;
        }

        out.add(lines.get(versionIndex));
        out.addAll(toDefineLines(defines));
        for (int i = 0; i < lines.size(); i++) {
            if (i == versionIndex || VERSION_PATTERN.matcher(lines.get(i)).matches()) {
                continue;
            }
            out.add(lines.get(i));
        }
        return out;
    }

    private static List<String> toDefineLines(Map<String, String> defines) {
        List<String> result = new ArrayList<>(defines.size());
        for (Map.Entry<String, String> e : defines.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                result.add("#define " + e.getKey());
            } else {
                result.add("#define " + e.getKey() + " " + e.getValue());
            }
        }
        return result;
    }

    /**
     * Drops the branches of {@code #if}/{@code #ifdef} conditionals the given macro set does not take, so a scanner
     * sees only the declarations the GPU will actually compile. <b>For directive extraction only</b> — the result is
     * not compilable source (every {@code #} line, {@code #version} and {@code #extension} included, is consumed).
     * <p>
     * Iris scans source that JCPP has already preprocessed ({@code ShaderPack}'s source provider feeds
     * {@code ProgramSet}, which runs {@code ConstDirectiveParser} over it), so a directive sitting in a dead branch is
     * simply not there. A raw-text regex instead takes the first textual match, which happily reads a value out of a
     * branch the pack disabled.
     * <p>
     * Resolve one stage at a time: define state and {@code #if} nesting must not leak from one file into the next, and
     * packs select which half of a shared body to compile with a {@code #define VERTEX_SHADER}/{@code FRAGMENT_SHADER}
     * at the top of each entry point. Note that resolving consumes the {@code #define} lines it evaluates, so
     * {@code #define}-form directives ({@code SHADOWRES}, {@code SHADOWFOV}) must still be read from the raw source.
     *
     * @return the resolved source, or {@code source} unchanged if the conditionals could not be evaluated
     */
    public static String resolveConditionals(String source, Map<String, String> defines) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        try {
            String resolved = PropertiesPreprocessor.preprocess(source, defines);
            // An unterminated #if can swallow the rest of the file; keep the raw source rather than scan nothing.
            return resolved.trim().isEmpty() ? source : resolved;
        } catch (RuntimeException e) {
            return source;
        }
    }

    /** A GLSL floating-point literal: {@code 1.0}, {@code .5}, {@code 0.}, {@code 1e-3}. */
    private static final Pattern FLOAT_LITERAL =
            Pattern.compile("(?<![A-Za-z0-9_.])(?:\\d+\\.\\d*|\\.\\d+|\\d+[eE][-+]?\\d+)");
    /** {@code defined X} / {@code defined(X)} — the one place an identifier must NOT be macro-expanded. */
    private static final Pattern DEFINED_OPERATOR =
            Pattern.compile("\\bdefined\\s*(?:\\(\\s*\\w+\\s*\\)|\\s+\\w+)");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern CONDITIONAL_DIRECTIVE =
            Pattern.compile("(?m)^([\\t ]*#[\\t ]*)(if|elif)\\b([^\\n]*)$");

    /**
     * Folds {@code #if}/{@code #elif} conditionals whose expression works out to a floating-point comparison into a
     * literal {@code 1}/{@code 0}, leaving every other directive for the driver.
     * <p>
     * The C (and therefore GLSL) preprocessor grammar is integer-only, so a driver rejects
     * {@code #if MOTION_BLUR > 0.0} outright — NVIDIA with {@code C0105: Syntax error in #if}, which took Clarity's
     * whole {@code composite.csh} down. Iris never hits this because it runs the entire source through JCPP before
     * the driver sees it, and JCPP accepts float literals by truncating them toward zero. Rather than take on a full
     * preprocessor, this resolves only the directives that cannot legally reach the driver, so everything else
     * (including {@code #ifdef} trees and line numbering, since each folded directive stays on its own line) is
     * untouched.
     * <p>
     * Macro state is tracked the way the driver would see it, one line at a time, so a macro defined differently in
     * two branches still resolves to the definition in force at the directive being folded. A conditional this
     * evaluator cannot parse counts as taken for that bookkeeping — the same conservative fallback
     * {@link #resolveConditionals} uses — and is never itself folded. Backslash-continued conditionals therefore fall
     * through untouched as well, which is correct: a directive spread over several lines cannot be replaced by a
     * single one without shifting every line number after it.
     */
    public static String foldFloatConditionals(String source, Map<String, String> defines) {
        if (source == null || source.indexOf('#') < 0) {
            return source;
        }
        Map<String, String> active = new LinkedHashMap<>(defines);
        StringBuilder out = new StringBuilder(source.length());
        // Nesting levels, each [0] = this branch active, [1] = some branch already taken.
        java.util.Deque<boolean[]> stack = new java.util.ArrayDeque<>();
        String[] lines = source.split("\n", -1);
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // The real preprocessor strips comments before it looks for directives, so a `#if` commented out with
            // /* */ is not one. Packs do exactly this (Clarity parks a dead #ifdef/#endif pair in a comment block);
            // interpreting those would desync the macro bookkeeping below from what the driver sees.
            boolean commented = inBlockComment;
            inBlockComment = advanceBlockComment(line, inBlockComment);
            out.append(commented ? line : foldDirective(line, active, stack));
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** {@return whether the line ends inside a block comment, given whether it started inside one} */
    private static boolean advanceBlockComment(String line, boolean inBlockComment) {
        for (int i = 0; i < line.length() - 1; i++) {
            if (inBlockComment) {
                if (line.charAt(i) == '*' && line.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
            } else if (line.charAt(i) == '/' && line.charAt(i + 1) == '/') {
                return false;
            } else if (line.charAt(i) == '/' && line.charAt(i + 1) == '*') {
                inBlockComment = true;
                i++;
            }
        }
        return inBlockComment;
    }

    private static String foldDirective(String line, Map<String, String> defines, java.util.Deque<boolean[]> stack) {
        Matcher conditional = CONDITIONAL_DIRECTIVE.matcher(line);
        if (conditional.matches()) {
            String expression = stripComment(conditional.group(3));
            boolean[] frame = "elif".equals(conditional.group(2)) ? stack.poll() : null;
            boolean alreadyTaken = frame != null && frame[1];
            Boolean value = PropertiesPreprocessor.tryEvaluateBooleanExpression(expression, defines).orElse(null);
            boolean malformed = value == null && !isContinued(expression) && isSyntacticallyInvalid(expression);
            boolean taken = !alreadyTaken && allActive(stack) && !malformed && (value == null || value);
            stack.push(new boolean[]{taken, alreadyTaken || taken});
            if (malformed || (value != null && expandsToFloat(expression, defines, 0))) {
                return conditional.group(1) + conditional.group(2) + " " + (!malformed && value ? "1" : "0");
            }
            return line;
        }

        String trimmed = line.trim();
        if (!trimmed.startsWith("#")) {
            return line;
        }
        // Comments come off first, as the real preprocessor does: `#endif // label` and `#else /* label */` are
        // idiomatic in packs, and a trailing label that stopped `#else` from being recognized would leave the
        // branch stack — and every define recorded after it — out of step with the driver.
        String directive = stripComment(trimmed.substring(1)).trim();
        if (directive.startsWith("ifdef ") || directive.startsWith("ifndef ")) {
            boolean defined = defines.containsKey(directive.substring(directive.indexOf(' ') + 1).trim());
            boolean taken = allActive(stack) && (directive.startsWith("ifdef ") == defined);
            stack.push(new boolean[]{taken, taken});
        } else if (directive.equals("else")) {
            boolean[] frame = stack.poll();
            boolean alreadyTaken = frame != null && frame[1];
            stack.push(new boolean[]{!alreadyTaken && allActive(stack), true});
        } else if (directive.equals("endif")) {
            stack.poll();
        } else if (directive.startsWith("define ") && allActive(stack)) {
            String body = directive.substring("define ".length()).trim();
            int space = body.indexOf(' ');
            int paren = body.indexOf('(');
            if (paren >= 0 && (space < 0 || paren < space)) {
                defines.put(body.substring(0, paren), "");
            } else if (space < 0) {
                defines.put(body, "");
            } else {
                defines.put(body.substring(0, space), body.substring(space + 1).trim());
            }
        } else if (directive.startsWith("undef ") && allActive(stack)) {
            defines.remove(directive.substring("undef ".length()).trim());
        }
        return line;
    }

    /**
     * Whether {@code expression} is something no preprocessor could accept, so folding it to {@code 0} is strictly
     * better than forwarding it to the driver.
     * <p>
     * This is deliberately narrow. "Our evaluator could not parse it" is <em>not</em> grounds to drop a branch — the
     * driver's preprocessor is the more capable one, and killing a live branch on a parser shortfall would be a far
     * worse failure than the one being fixed. Unbalanced parentheses are the one signal that needs no judgement: no
     * valid {@code #if} expression has them, so the driver is guaranteed to reject the directive as well, and
     * rejecting it costs the entire program rather than one branch.
     * <p>
     * Real case: Pastel v1.200 ships {@code #if (in(biome, BIOME_SOUL_SAND_VALLEY)} in
     * {@code lib/atmospherics/fog.glsl} — shaders.properties custom-uniform syntax pasted into GLSL, missing a
     * paren, and sitting in the {@code #else} of an {@code #ifdef NETHER}, so the overworld build reaches it. NVIDIA
     * answers {@code C0105: Syntax error in #if} and Pastel's whole {@code deferred1} pass — its lighting and fog —
     * is dropped. The branch cannot have been intended to compile, so taking it as false is what the pack means.
     */
    /**
     * Whether this directive is only the first line of a backslash-continued one, so the rest of its expression — and
     * very likely the parentheses that balance it — is on the following lines.
     * <p>
     * This guard is what keeps {@link #isSyntacticallyInvalid} honest, and it is not theoretical: Photon has three of
     * these ({@code gbuffers_all_solid.fsh:308}, {@code gbuffers_all_translucent.vsh:109},
     * {@code include/vertex/utility.glsl:21}, e.g. {@code #elif ( \}). Judged one line at a time every one of them
     * looks unbalanced, and folding them to {@code 0} would silently delete live branches from Photon's main gbuffer
     * programs. A sweep of all 22 installed packs finds exactly these three continuations and one genuinely broken
     * directive (Pastel's), which is the split this pair of checks has to reproduce.
     */
    private static boolean isContinued(String expression) {
        String trimmed = expression.trim();
        return trimmed.endsWith("\\");
    }

    private static boolean isSyntacticallyInvalid(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth < 0) {
                return true;
            }
        }
        return depth != 0;
    }

    private static boolean allActive(java.util.Deque<boolean[]> stack) {
        for (boolean[] frame : stack) {
            if (!frame[0]) {
                return false;
            }
        }
        return true;
    }

    /** Truncates at the first comment opener of either kind; a directive never carries meaning past one. */
    private static String stripComment(String text) {
        int line = text.indexOf("//");
        int block = text.indexOf("/*");
        int comment = line < 0 ? block : (block < 0 ? line : Math.min(line, block));
        return comment < 0 ? text : text.substring(0, comment);
    }

    /** Whether the expression contains a float literal once its macros are substituted, as the driver would see it. */
    private static boolean expandsToFloat(String expression, Map<String, String> defines, int depth) {
        if (FLOAT_LITERAL.matcher(expression).find()) {
            return true;
        }
        if (depth >= 8) {
            return false;
        }
        Matcher identifiers = IDENTIFIER.matcher(DEFINED_OPERATOR.matcher(expression).replaceAll(""));
        while (identifiers.find()) {
            String value = defines.get(identifiers.group());
            if (value != null && !value.isEmpty() && expandsToFloat(value, defines, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Seam for later phases: rewrite legacy fixed-function built-ins to explicit {@code in}/attribute names.
     * Because Impetus renders chunks through VAOs, the fixed-function attribute slots are never populated, so a
     * GLSL-150+ translation needs to map e.g. {@code gl_MultiTexCoord0 -> vec4(mc_midTexCoord, 0.0, 1.0)}.
     * Not implemented in Phase 1; returns the input unchanged.
     */
    public static List<String> replaceLegacyBuiltins(List<String> lines, Map<String, String> attributeBindings) {
        // Intentionally a no-op for Phase 1. Kept as an explicit extension point.
        return lines;
    }

    /** {@code #extension GL_FOO : enable}, in any legal spacing. Trailing {@code \r?} for the reason on VERSION_PATTERN. */
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("^\\s*#\\s*extension\\s+.*\\r?$");

    /**
     * Every rewrite that must happen to pack GLSL between the transformers and {@code glShaderSource} — the ones that
     * exist because packs are authored against NVIDIA and this port also has to satisfy Mesa. Both are no-ops unless
     * the source actually trips the rule.
     * <p>
     * <b>Call this from every path that hands pack source to the driver.</b> There are two unrelated {@code GlShader}
     * classes — {@code iris.gl.shader.GlShader} and the engine's {@code engine.impl.gl.shader.GlShader} — and the
     * terrain/shadow override uses the engine one while every other Iris path uses the Iris one. Hanging these calls
     * off the Iris constructor and calling it "the only point every path passes through" was wrong by exactly that one
     * path, which happens to be the one that compiles {@code gbuffers_terrain}/{@code shadow}: the misplaced-{@code
     * #extension} fix shipped in {@code 0323d2aaa} therefore never ran on the programs whose logs motivated it, and
     * the reports kept coming in unchanged. Bundling them here gives the two call sites one name to share.
     */
    public static String finalizeForDriver(String name, String source) {
        return rewriteIntegerSamplerLookups(name, hoistExtensionDirectives(source));
    }

    /**
     * Moves every {@code #extension} directive up to just below {@code #version}, which is where GLSL requires them.
     * <p>
     * The spec forbids an {@code #extension} directive after any non-preprocessor token. NVIDIA ignores that and
     * honours them anywhere; <b>Mesa enforces it</b>. Packs are overwhelmingly authored against NVIDIA, so they put
     * the directive at the top of the <em>include</em> that needs it and never notice — Complementary's
     * {@code lib/materials/materialMethods/worldSpaceRef.glsl} opens with
     * {@code #extension GL_ARB_shader_image_load_store : enable}, and once {@code #include} flattening has run, that
     * line sits in the middle of the program far below real declarations.
     * <p>
     * Iris solves the same problem in {@code JcppProcessor}, which marks {@code #version}/{@code #extension},
     * collects them during preprocessing and re-emits them at the top; its comment names the exact motivation —
     * "for shader packs written on lenient drivers that allow #extension directives to be placed anywhere to work on
     * strict drivers like Mesa".
     * <p>
     * Only rewrites when a directive genuinely appears after real code, so already-well-formed packs reach the driver
     * byte-identical. Directives are de-duplicated, keep their relative order, and leave a blank line behind so
     * driver error messages still point at the right line. Hoisting one out of an {@code #if} guard is safe in
     * practice because the behaviour is virtually always {@code : enable}, which is defined to warn-and-continue when
     * the extension is unavailable rather than fail.
     */
    public static String hoistExtensionDirectives(String source) {
        if (source == null || !source.contains("#extension")) {
            return source;
        }

        String[] lines = source.split("\n", -1);
        int firstRealToken = indexOfFirstNonPreprocessorLine(lines);
        if (firstRealToken < 0) {
            return source;
        }

        boolean misplaced = false;
        for (int i = firstRealToken; i < lines.length; i++) {
            if (EXTENSION_PATTERN.matcher(lines[i]).matches()) {
                misplaced = true;
                break;
            }
        }
        if (!misplaced) {
            return source;
        }

        List<String> directives = new ArrayList<>();
        List<String> kept = new ArrayList<>(lines.length);
        int versionLine = -1;
        for (String line : lines) {
            if (EXTENSION_PATTERN.matcher(line).matches()) {
                String directive = line.trim();
                if (!directives.contains(directive)) {
                    directives.add(directive);
                }
                kept.add("");
                continue;
            }
            if (versionLine < 0 && VERSION_PATTERN.matcher(line).matches()) {
                versionLine = kept.size();
            }
            kept.add(line);
        }

        kept.addAll(versionLine < 0 ? 0 : versionLine + 1, directives);
        return String.join("\n", kept);
    }

    /**
     * The compatibility-profile texture lookups and the core built-in that replaces each. Every entry is matched with
     * a mandatory {@code (} immediately after the name, so {@code texture2D} never matches inside {@code texture2DLod}
     * and the map order carries no meaning.
     */
    private static final Map<String, String> LEGACY_TEXTURE_LOOKUPS;

    static {
        Map<String, String> lookups = new LinkedHashMap<>();
        lookups.put("texture1D", "texture");
        lookups.put("texture2D", "texture");
        lookups.put("texture3D", "texture");
        lookups.put("textureCube", "texture");
        lookups.put("texture1DLod", "textureLod");
        lookups.put("texture2DLod", "textureLod");
        lookups.put("texture3DLod", "textureLod");
        lookups.put("textureCubeLod", "textureLod");
        lookups.put("texture1DProj", "textureProj");
        lookups.put("texture2DProj", "textureProj");
        lookups.put("texture3DProj", "textureProj");
        lookups.put("texture1DProjLod", "textureProjLod");
        lookups.put("texture2DProjLod", "textureProjLod");
        lookups.put("texture3DProjLod", "textureProjLod");
        LEGACY_TEXTURE_LOOKUPS = Collections.unmodifiableMap(lookups);
    }

    /**
     * An integer sampler type followed by whatever it declares, up to the {@code ;} or {@code )} that ends it.
     * <p>
     * Deliberately not anchored to {@code uniform} at the start of a line. A pack that wraps its image reads in a
     * helper — {@code uint readVoxel(usampler3D s, vec3 p) { return texture2D(s, p).r; }} — declares the sampler as a
     * <em>function parameter</em>, and the call needing the rewrite is inside that function. Anchoring would see the
     * uniform and miss the parameter, i.e. miss the very call site the driver rejects.
     */
    private static final Pattern INTEGER_SAMPLER_DECLARATION =
            Pattern.compile("\\b[ui]sampler[A-Za-z0-9]*[ \\t]+([^;)\\n]+)");

    /**
     * Points the legacy {@code texture2D}-family lookups at their core equivalents, but only where the sampler being
     * read is one the source itself declares as an <em>integer</em> sampler ({@code usampler*}/{@code isampler*}).
     * <p>
     * The compatibility profile keeps {@code texture2D} alive, which is why the modern-pack paths deliberately leave
     * pack bodies alone rather than performing Iris's blanket rename — but it keeps it alive <b>only for float
     * samplers</b>. There is no {@code texture2D(usampler2D, vec2)} overload in any GLSL version. NVIDIA's compiler
     * resolves the call anyway; <b>Mesa rejects it</b>, exactly as it rejects a misplaced {@code #extension} (see
     * {@link #hoistExtensionDirectives}). Packs are authored against NVIDIA and never see it.
     * <p>
     * Complementary Unbound reads its colored-lighting voxel and puddle images with {@code texture2D}, so on Mesa
     * {@code gbuffers_terrain_solid}/{@code _cutout_mip} failed to compile the moment colored lighting was switched on
     * ({@code error: no matching function for call to `texture2D(usampler2D, vec2)'}). Terrain then silently fell back
     * to Impetus's own chunk shader, which runs none of the pack's vertex stage — so leaves and grass stopped waving
     * while the rest of the frame still looked shaded, and nothing in the log named waving at all.
     * <p>
     * Iris reaches the same end state from the other direction: it compiles every pack at {@code #version 330 core},
     * where the legacy names do not exist, so {@code CommonTransformer} renames all of them unconditionally and the
     * generic {@code texture()} overload resolves for integer samplers as a side effect. Restricting the rename to
     * integer samplers is what makes it safe to apply here, where the legacy names are still live and a pack may still
     * own an identifier called {@code texture}.
     * <p>
     * Deliberately keyed off the declaration rather than the call site, and deliberately whitespace-tolerant only
     * within a line: the sampler must be the <em>entire</em> first argument, so {@code texture2D(f(voxel_sampler), uv)}
     * is left alone, and no rewrite can shift a line number out from under a driver error message.
     */
    public static String rewriteIntegerSamplerLookups(String name, String source) {
        if (source == null || !source.contains("sampler")) {
            return source;
        }
        Set<String> samplers = integerSamplerNames(source);
        if (samplers.isEmpty()) {
            return source;
        }

        StringBuilder alternation = new StringBuilder();
        for (String sampler : samplers) {
            if (alternation.length() > 0) {
                alternation.append('|');
            }
            alternation.append(Pattern.quote(sampler));
        }

        String result = source;
        int rewritten = 0;
        for (Map.Entry<String, String> lookup : LEGACY_TEXTURE_LOOKUPS.entrySet()) {
            if (!result.contains(lookup.getKey())) {
                continue;
            }
            // The trailing (?=[,)]) is the correctness guard: the sampler (optionally with one flat subscript, for a
            // declared array of them) must be the entire first argument, so a sampler passed through a call of the
            // pack's own — texture2D(pick(voxel_sampler), uv) — is left for the driver to resolve as authored.
            Matcher call = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(lookup.getKey())
                    + "[ \\t]*\\([ \\t]*(" + alternation + ")(\\[[^\\[\\]]*\\])?[ \\t]*(?=[,)])").matcher(result);
            StringBuffer rewrite = new StringBuffer();
            while (call.find()) {
                String subscript = call.group(2) == null ? "" : call.group(2);
                call.appendReplacement(rewrite,
                        Matcher.quoteReplacement(lookup.getValue() + "(" + call.group(1) + subscript));
                rewritten++;
            }
            call.appendTail(rewrite);
            result = rewrite.toString();
        }

        if (rewritten > 0) {
            LOGGER.info("[Iris] Program '{}': repointed {} legacy texture lookup(s) on integer sampler(s) {} to the core built-in (no texture2D overload exists for them)",
                    name, rewritten, samplers);
        }
        return result;
    }

    /** {@return every identifier the source declares as a {@code usampler*}/{@code isampler*}} */
    private static Set<String> integerSamplerNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher declaration = INTEGER_SAMPLER_DECLARATION.matcher(source);
        while (declaration.find()) {
            String[] declarators = declaration.group(1).split(",");
            for (int i = 0; i < declarators.length; i++) {
                String declarator = declarators[i].trim();
                String identifier = identifierPrefix(declarator);
                if (identifier.isEmpty()) {
                    break;
                }
                // Only the first declarator is certain to belong to the sampler type. A comma continues the same
                // declaration in `usampler2D a, b;` (bare names) but starts a new parameter in
                // `usampler3D s, vec3 p` (a name preceded by its own type), so stop at the first non-bare one.
                if (i > 0 && !isBareDeclarator(declarator, identifier)) {
                    break;
                }
                names.add(identifier);
            }
        }
        return names;
    }

    /** Whether {@code declarator} is just {@code identifier}, optionally with an array subscript after it. */
    private static boolean isBareDeclarator(String declarator, String identifier) {
        String remainder = declarator.substring(identifier.length()).trim();
        return remainder.isEmpty() || (remainder.startsWith("[") && remainder.endsWith("]"));
    }

    /** {@code "shadowVoxels[2]"} -> {@code "shadowVoxels"}; anything not starting with an identifier yields "". */
    private static String identifierPrefix(String declarator) {
        int end = 0;
        while (end < declarator.length()
                && (Character.isLetterOrDigit(declarator.charAt(end)) || declarator.charAt(end) == '_')) {
            end++;
        }
        return declarator.substring(0, end);
    }

    /**
     * {@return the index of the first line carrying a real GLSL token, or -1 if the source is all directives}
     * <p>
     * Skips blank lines, {@code //} comments, {@code #} directives and block comments — the only things GLSL permits
     * to precede an {@code #extension}.
     */
    private static int indexOfFirstNonPreprocessorLine(String[] lines) {
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (inBlockComment) {
                int end = line.indexOf("*/");
                if (end < 0) {
                    continue;
                }
                line = line.substring(end + 2).trim();
                inBlockComment = false;
            }
            while (line.contains("/*")) {
                int start = line.indexOf("/*");
                int end = line.indexOf("*/", start + 2);
                if (end < 0) {
                    inBlockComment = true;
                    line = line.substring(0, start).trim();
                    break;
                }
                line = (line.substring(0, start) + line.substring(end + 2)).trim();
            }
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /** Convenience: a stable, insertion-ordered map suitable for {@link #injectDefines}. */
    public static Map<String, String> newDefineMap() {
        return new LinkedHashMap<>();
    }
}
