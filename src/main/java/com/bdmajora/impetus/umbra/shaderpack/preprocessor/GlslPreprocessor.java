package com.bdmajora.impetus.umbra.shaderpack.preprocessor;


import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Light-touch GLSL source transformation run after #include flattening, before GL compilation.
// Handles: finding #version, injecting #define macros after it, and injecting a default #version (120) when missing.
// Legacy fixed-function built-in substitution (gl_MultiTexCoord0 etc) is NOT done here - see replaceLegacyBuiltins.
public final class GlslPreprocessor {

    // Trailing \r? is load-bearing: callers split on "\n" alone and call matches(), which must consume the whole
    // line. Without it, Windows line endings failed every #version match, silently breaking detectVersion,
    // injectDefines and hoistExtensionDirectives at once.
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^\\s*#version\\s+(\\d+)(?:\\s+(\\w+))?.*\\r?$");

    // Default GLSL version assumed for 1.12.2-era packs that omit a #version directive
    public static final int DEFAULT_VERSION = 120;

    private GlslPreprocessor() {
    }

    // Returns the version declared by the first #version directive, or -1 if none
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

    // Inserts defines right after #version (prepending a default #version if none exists)
    // defines is ordered macro name -> value; an empty value yields a bare #define NAME
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

    // Drops #if/#ifdef branches the macro set doesn't take, so a scanner only sees what the GPU would compile.
    // For directive extraction only - result is not compilable (every # line is consumed).
    // Call once per stage: define state/#if nesting must not leak between files, and resolving consumes #define
    // lines it evaluates, so #define-form directives (SHADOWRES, SHADOWFOV) still need reading from raw source.
    // Returns source unchanged if the conditionals couldn't be evaluated.
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

    // A GLSL floating-point literal: 1.0, .5, 0., 1e-3
    private static final Pattern FLOAT_LITERAL =
            Pattern.compile("(?<![A-Za-z0-9_.])(?:\\d+\\.\\d*|\\.\\d+|\\d+[eE][-+]?\\d+)");
    // "defined X" / "defined(X)" - the one place an identifier must NOT be macro-expanded
    private static final Pattern DEFINED_OPERATOR =
            Pattern.compile("\\bdefined\\s*(?:\\(\\s*\\w+\\s*\\)|\\s+\\w+)");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern CONDITIONAL_DIRECTIVE =
            Pattern.compile("(?m)^([\\t ]*#[\\t ]*)(if|elif)\\b([^\\n]*)$");

    // Folds #if/#elif conditionals that resolve to a float comparison into a literal 1/0, leaving everything else
    // for the driver. The C preprocessor grammar is integer-only, so `#if MOTION_BLUR > 0.0` crashes NVIDIA with
    // "C0105: Syntax error in #if" (took down Clarity's composite.csh). Umbra avoids this by running everything
    // through JCPP first, which truncates float literals; this does the narrow equivalent without a full preprocessor.
    // Macro state is tracked line-by-line like the driver sees it. Anything this evaluator can't parse is treated as
    // taken (same conservative fallback as resolveConditionals) and left unfolded - includes backslash-continued
    // conditionals, since folding those would shift line numbers.
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

    // Whether the line ENDS inside a block comment, given whether it started inside one — carried across lines so
    // a directive commented out by a multi-line block is not evaluated
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

    // Whether this directive is only the FIRST line of a backslash-continued one, so the rest of its expression —
    // and very likely the parentheses that balance it — is on the lines that follow
    // This guard is what keeps isSyntacticallyInvalid below honest, and it is not theoretical: Photon has three of
    // these, in gbuffers_all_solid.fsh:308, gbuffers_all_translucent.vsh:109 and include/vertex/utility.glsl:21,
    // e.g. a bare `#elif ( \`
    // Judged one line at a time every one of them looks unbalanced, and folding them to 0 would silently delete
    // live branches from Photon's main gbuffer programs
    // A sweep of all 22 installed packs finds exactly these three continuations and one genuinely broken directive
    // (Pastel's), which is the split this pair of checks has to reproduce
    private static boolean isContinued(String expression) {
        String trimmed = expression.trim();
        return trimmed.endsWith("\\");
    }

    // Whether the expression is something NO preprocessor could accept, making it strictly better to fold to 0 than
    // to forward to the driver
    // Deliberately narrow: "our evaluator could not parse it" is NOT grounds to drop a branch, because the driver's
    // preprocessor is the more capable one and killing a live branch on a parser shortfall would be a worse failure
    // than the one being fixed
    // Unbalanced parentheses are the one signal needing no judgement — no valid #if expression has them, so the
    // driver is guaranteed to reject the directive too, and that rejection costs the ENTIRE program rather than one
    // branch
    // Real case: Pastel v1.200 ships `#if (in(biome, BIOME_SOUL_SAND_VALLEY)` in lib/atmospherics/fog.glsl —
    // shaders.properties custom-uniform syntax pasted into GLSL, missing a paren, sitting in the #else of an
    // #ifdef NETHER so the overworld build reaches it. NVIDIA answers C0105 "Syntax error in #if" and Pastel's
    // whole deferred1 pass, its lighting and fog, is dropped
    // The branch cannot have been intended to compile, so taking it as false is what the pack means
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

    // Truncates at the first comment opener of either kind — a directive never carries meaning past one
    private static String stripComment(String text) {
        int line = text.indexOf("//");
        int block = text.indexOf("/*");
        int comment = line < 0 ? block : (block < 0 ? line : Math.min(line, block));
        return comment < 0 ? text : text.substring(0, comment);
    }

    // Whether the expression contains a float literal ONCE ITS MACROS ARE SUBSTITUTED, i.e. as the driver would
    // see it — a macro expanding to 0.5 makes an integer-looking conditional a float one
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

    // Unimplemented seam: rewriting legacy fixed-function built-ins to explicit in/attribute names
    // The need is real — chunks render through VAOs, so the fixed-function attribute slots are never populated, and
    // a GLSL-150+ translation would have to map gl_MultiTexCoord0 onto vec4(mc_midTexCoord, 0.0, 1.0)
    // In practice the terrain and fullscreen transformers each do their own version of this, so nothing calls it;
    // it returns the input unchanged
    public static List<String> replaceLegacyBuiltins(List<String> lines, Map<String, String> attributeBindings) {
        // Intentionally a no-op for Phase 1. Kept as an explicit extension point.
        return lines;
    }

    // #extension GL_FOO : enable, in any legal spacing
    // The trailing \r? is for the same reason VERSION_PATTERN has one: pack files ship with CRLF line endings and
    // the carriage return would otherwise defeat the end anchor
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("^\\s*#\\s*extension\\s+.*\\r?$");

    // Every rewrite that must happen to pack GLSL between the transformers and glShaderSource — the ones that exist
    // because packs are authored against NVIDIA while this port also has to satisfy Mesa
    // All of them are no-ops unless the source actually trips the rule, so this is safe to call unconditionally
    //
    // Call this from EVERY path that hands pack source to the driver. There are two unrelated GlShader classes here
    // — umbra.gl.shader.GlShader and the engine's engine.impl.gl.shader.GlShader — and the terrain/shadow override
    // uses the engine one while every other Umbra path uses the Umbra one
    // Hanging these calls off the Umbra constructor and calling it "the only point every path passes through" was
    // wrong by exactly that one path, which happens to be the one compiling gbuffers_terrain and shadow. The
    // misplaced-#extension fix shipped in 0323d2aaa therefore never ran on the programs whose logs motivated it,
    // and the reports kept coming in unchanged
    // Bundling the rewrites here gives the two call sites one name to share
    public static String finalizeForDriver(String name, String source) {
        return rewriteIntegerSamplerLookups(name, hoistExtensionDirectives(source));
    }

    // Moves every #extension directive up to just below #version, which is where GLSL requires them
    //
    // The spec forbids an #extension after any non-preprocessor token. NVIDIA ignores that and honours them
    // anywhere; MESA ENFORCES IT. Packs are overwhelmingly authored against NVIDIA, so they put the directive at
    // the top of the INCLUDE that needs it and never notice
    // Complementary's lib/materials/materialMethods/worldSpaceRef.glsl opens with
    // `#extension GL_ARB_shader_image_load_store : enable`, and once #include flattening has run that line sits in
    // the middle of the program, far below real declarations
    //
    // Iris solves the same problem in JcppProcessor, marking #version and #extension, collecting them during
    // preprocessing and re-emitting them at the top — its own comment names the identical motivation, packs written
    // on lenient drivers needing to work on strict ones like Mesa
    //
    // Only rewrites when a directive genuinely appears after real code, so an already-well-formed pack reaches the
    // driver byte-identical
    // Directives are de-duplicated, keep their relative order, and leave a blank line behind so driver error
    // messages still point at the right line numbers
    // Hoisting one out of an #if guard is safe in practice because the behaviour is virtually always `: enable`,
    // which is defined to warn and continue when the extension is unavailable rather than fail
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

    // The compatibility-profile texture lookups and the core built-in replacing each
    // Every entry is matched with a mandatory ( immediately after the name, so texture2D can never match inside
    // texture2DLod — which is what makes the map's iteration order irrelevant here
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

    // An integer sampler type followed by whatever it declares, up to the ; or ) that ends it
    // Deliberately NOT anchored to `uniform` at the start of a line. A pack that wraps its image reads in a helper —
    // `uint readVoxel(usampler3D s, vec3 p) { return texture2D(s, p).r; }` — declares the sampler as a FUNCTION
    // PARAMETER, and the call needing the rewrite is inside that function
    // Anchoring would find the uniform and miss the parameter, i.e. miss the very call site the driver rejects
    private static final Pattern INTEGER_SAMPLER_DECLARATION =
            Pattern.compile("\\b[ui]sampler[A-Za-z0-9]*[ \\t]+([^;)\\n]+)");

    // Points the legacy texture2D-family lookups at their core equivalents, but ONLY where the sampler being read
    // is one this source itself declares as an integer sampler — usampler* or isampler*
    //
    // The compatibility profile keeps texture2D alive, which is exactly why the modern-pack paths leave pack bodies
    // alone rather than doing Iris's blanket rename. But it keeps it alive only FOR FLOAT SAMPLERS: there is no
    // texture2D(usampler2D, vec2) overload in any GLSL version
    // NVIDIA's compiler resolves the call anyway; Mesa rejects it, exactly as it rejects a misplaced #extension.
    // Packs are authored against NVIDIA and never see it
    //
    // Complementary Unbound reads its coloured-lighting voxel and puddle images with texture2D, so on Mesa
    // gbuffers_terrain_solid and _cutout_mip failed to compile the moment coloured lighting was switched on, with
    // "no matching function for call to texture2D(usampler2D, vec2)"
    // Terrain then fell back to Impetus's own chunk shader, which runs none of the pack's vertex stage — so leaves
    // and grass stopped waving while the rest of the frame still looked shaded, and nothing in the log named waving
    //
    // Iris reaches the same end state from the other direction: it compiles every pack at #version 330 core where
    // the legacy names do not exist, so CommonTransformer renames all of them unconditionally and the generic
    // texture() overload resolves for integer samplers as a side effect
    // Restricting the rename to integer samplers is what makes it safe HERE, where the legacy names are still live
    // and a pack may still own an identifier called `texture`
    //
    // Keyed off the DECLARATION rather than the call site, and whitespace-tolerant only within a line: the sampler
    // must be the entire first argument, so texture2D(f(voxel_sampler), uv) is left alone, and no rewrite can shift
    // a line number out from under a driver error message
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
            }
            call.appendTail(rewrite);
            result = rewrite.toString();
        }

        return result;
    }

    // Every identifier this source declares as a usampler* or isampler*, whether as a uniform or as a function
    // parameter
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

    // Whether the declarator is just that identifier, optionally with an array subscript — anything more means the
    // match caught something other than a plain declaration
    private static boolean isBareDeclarator(String declarator, String identifier) {
        String remainder = declarator.substring(identifier.length()).trim();
        return remainder.isEmpty() || (remainder.startsWith("[") && remainder.endsWith("]"));
    }

    // Strips an array subscript: "shadowVoxels[2]" gives "shadowVoxels". Anything not starting with an identifier
    // at all yields the empty string, which the caller treats as no match
    private static String identifierPrefix(String declarator) {
        int end = 0;
        while (end < declarator.length()
                && (Character.isLetterOrDigit(declarator.charAt(end)) || declarator.charAt(end) == '_')) {
            end++;
        }
        return declarator.substring(0, end);
    }

    // The index of the first line carrying a real GLSL token, or -1 when the source is nothing but directives —
    // which is the point a hoisted #extension has to land before
    // Skips blank lines, // comments, # directives and block comments, since those are the only things GLSL permits
    // to precede an #extension
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

    // A stable, INSERTION-ORDERED map for the define set — order matters because the defines are emitted into the
    // shader in map order, and a define referencing an earlier one has to come after it
    public static Map<String, String> newDefineMap() {
        return new LinkedHashMap<>();
    }
}
