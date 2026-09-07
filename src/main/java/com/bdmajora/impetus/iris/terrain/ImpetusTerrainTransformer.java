package com.bdmajora.impetus.iris.terrain;

import com.bdmajora.impetus.iris.gl.program.DrawBuffers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms an OptiFine-style GLSL-120 {@code gbuffers_terrain} program so it can run as Impetus/Impetus's terrain
 * shader — i.e. consume Impetus's <em>uncompressed</em> chunk vertex format ({@code a_PosId}/{@code a_Color}/
 * {@code a_TexCoord}/{@code a_LightCoord}) and matrices ({@code u_ModelViewMatrix}/{@code u_ProjectionMatrix}/
 * {@code u_RegionOffset}) instead of fixed-function state.
 * <p>
 * This is the 1.12.2 analogue of modern Iris's Sodium terrain transformation. The pack cannot read Impetus's
 * integer-packed vertex attributes at GLSL 120, so the shader is lifted to {@code #version 330 core} and the classic
 * built-ins are remapped: the pack's {@code main} is renamed to {@code irisMain}, and a generated {@code main} decodes
 * the Impetus vertex into globals that {@code #define}d built-ins ({@code gl_Vertex}, {@code gl_ModelViewMatrix},
 * {@code gl_MultiTexCoord0}, …) point at.
 * <p>
 * The generated fragment output array is wide enough for Iris's logical color targets; the framebuffer routes the
 * shader-pack's dense {@code gl_FragData} slots to the logical targets named by {@code DRAWBUFFERS}.
 */
public final class ImpetusTerrainTransformer {
    private static final Pattern VERSION = Pattern.compile("^\\s*#version[^\\n]*\\n", Pattern.MULTILINE);

    private ImpetusTerrainTransformer() {
    }

    /** Vertex prologue: Impetus attributes/uniforms + vertex decode + gl_* built-in aliases + main() wrapper. */
    private static final String VERTEX_PROLOGUE = String.join("\n",
            "#version 330 core",
            "// ---- Impetus/Iris terrain bridge (generated) ----",
            "in vec3 a_PosId;",
            "in vec4 a_Color;",
            "in vec2 a_TexCoord;",
            "in uint a_LightCoord;",
            "in vec4 iris_Normal;",      // true face normal, NormI8 (normalized signed bytes)
            "in vec4 iris_Tangent;",     // at_tangent, w = handedness
            "in vec2 iris_MidTexCoord;", // centre of the quad's texture region in atlas UV (not the sprite centre)
            "in vec4 iris_BlockInfo;",   // mc_Entity: (block id, render type, metadata, 1)
            "in vec4 iris_MidBlock;",    // at_midBlock: xyz offset-to-block-center * 64, w block emission
            "uniform mat4 u_ModelViewMatrix;",
            "uniform mat4 u_ProjectionMatrix;",
            "uniform vec3 u_RegionOffset;",
            "",
            "uvec3 _iris_relChunk(uint pos) { return (uvec3(pos) >> uvec3(5u,0u,2u)) & uvec3(7u,3u,7u); }",
            "vec3 _iris_drawTranslation(uint pos) { return vec3(_iris_relChunk(pos)) * 16.0; }",
            "// GLSL-120 shadow2D returned vec4; 330's texture() on a shadow sampler returns float. Wrap so .x/.z work.",
            "vec4 iris_shadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }",
            "vec4 iris_shadow2DLod(sampler2DShadow s, vec3 p, float l) { return vec4(textureLod(s, p, l)); }",
            "",
            "vec4 iris_Vertex;",
            "vec4 iris_Color;",
            "vec4 iris_MultiTexCoord0;",
            "vec4 iris_MultiTexCoord1;",
            "vec4 iris_MultiTexCoord2 = vec4(0.0, 0.0, 0.0, 1.0);",
            "vec4 iris_MultiTexCoord3 = vec4(0.0, 0.0, 0.0, 1.0);",
            "vec4 iris_MidTexFull;",
            "vec4 iris_EntityFull;",
            "// The matrix built-ins alias the uniforms directly (as expressions, not uniform-initialized globals —",
            "// global initializers must be constant expressions in GLSL 330; drivers that accept them may evaluate",
            "// them before uniforms are loaded, collapsing every vertex to the origin).",
            "// gl_TextureMatrix[1] is vanilla's lightmap matrix (scale 1/256, translate 8/256): raw 0..240 lightmap",
            "// coords -> 0..1 UVs. The rest are identity (the block atlas uses untransformed coords).",
            "const mat4 iris_LightmapTextureMatrix = mat4(",
            "    vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0),",
            "    vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0));",
            "mat4 iris_TextureMatrix[8] = mat4[8](mat4(1.0), iris_LightmapTextureMatrix,",
            "    mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0));",
            "",
            "#define gl_Vertex iris_Vertex",
            "#define gl_Color iris_Color",
            "#define gl_MultiTexCoord0 iris_MultiTexCoord0",
            "#define gl_MultiTexCoord1 iris_MultiTexCoord1",
            "#define gl_MultiTexCoord2 iris_MultiTexCoord2",
            "#define gl_MultiTexCoord3 iris_MultiTexCoord3",
            "#define gl_Normal (iris_Normal.xyz)",
            "#define gl_ModelViewMatrix u_ModelViewMatrix",
            "#define gl_ProjectionMatrix u_ProjectionMatrix",
            "#define gl_ModelViewProjectionMatrix (u_ProjectionMatrix * u_ModelViewMatrix)",
            "#define gl_NormalMatrix (mat3(transpose(inverse(u_ModelViewMatrix))))",
            "#define gl_TextureMatrix iris_TextureMatrix",
            "#define ftransform() (u_ProjectionMatrix * (u_ModelViewMatrix * iris_Vertex))",
            "out float iris_FogFragCoord;",
            "#define gl_FogFragCoord iris_FogFragCoord",
            // gl_Fog.* stand-ins: real per-frame uniforms (fed by CommonUniforms), NOT constants. See the
            // matching note in FullscreenTransformer — const 0.0/1.0/vec4(0) forces full-strength fog for
            // packs that read gl_Fog.start/end/color. Unused ones are stripped by the compiler.
            "uniform vec4 iris_FogColor;",
            "uniform float iris_FogDensity;",
            "uniform float iris_FogStart;",
            "uniform float iris_FogEnd;",
            // gl_Fog.scale is inlined as an expression by FogParameters (Iris's 1/(end-start)), so there is
            // deliberately no iris_FogScale declaration here.
            "out vec4 iris_TexCoordArr[4];",
            "#define gl_TexCoord iris_TexCoordArr",
            "// OptiFine packs rely on fixed-function GL_ALPHA_TEST for cutout transparency, but Impetus disables it",
            "// and discards in-shader instead; mirror its per-material cutoff (bits 1-2 of the material byte).",
            "const float[4] _IRIS_ALPHA_CUTOFF = float[4](0.0, 0.1, 0.5, 1.0);",
            "flat out float iris_AlphaCutoff;",
            // DEBUG: 1.0 on any vertex whose clip position came out non-finite (see the NaN/Inf guard in main()).
            // Interpolated (not flat) so the fragment tint reveals the whole flung triangle, not just its provoking vertex.
            "out float iris_nanFlag;",
            // DEBUG: the raw mc_Entity.x (pack block id) this vertex carried, forwarded so the fragment stage can
            // report it. flat, not smooth: it is an integer id, and interpolating between two ids yields a third that
            // matches neither. Declared unconditionally, like iris_nanFlag, so enabling the probe cannot change the
            // varying layout — a diagnostic that perturbs what it measures is worthless.
            "flat out float iris_blockIdProbe;",
            // DEBUG: this vertex's sky light, normalized the way the packs themselves normalize it (raw 0..240 -> 0..1),
            // so it can be compared directly against a pack's lightmap thresholds. flat for the same reason as above,
            // and because vertex-stage effects sample the per-vertex value, not the interpolated one.
            "flat out float iris_skyLightProbe;",
            "// ---- end generated prologue ----",
            ""
    ) + "\n";

    /**
     * The generated vertex main is APPENDED after the pack body: the hoisted global initializers it runs reference
     * pack globals/uniforms that must already be declared above it.
     */
    private static String vertexMain(String hoistedAssignments) {
        return "\nvoid main() {\n"
                + "    uint lightData = a_LightCoord;\n" // 'packed' is a reserved word in GLSL 330
                + "    uint drawId = (lightData >> 8u) & 0xFFu;\n"
                + "    vec3 pos = a_PosId + u_RegionOffset + _iris_drawTranslation(drawId);\n"
                + "    iris_Vertex = vec4(pos, 1.0);\n"
                + "    iris_Color = a_Color;\n"
                + "    iris_MultiTexCoord0 = vec4(a_TexCoord, 0.0, 1.0);\n"
                + "    uint blockLight = (lightData >> 16u) & 0xFFu;\n"
                + "    uint skyLight = (lightData >> 24u) & 0xFFu;\n"
                + "    iris_MultiTexCoord1 = vec4(float(blockLight), float(skyLight), 0.0, 1.0);\n"
                + "    iris_MidTexFull = vec4(iris_MidTexCoord, 0.0, 1.0);\n"
                + "    iris_EntityFull = iris_BlockInfo;\n"
                + "    iris_blockIdProbe = iris_BlockInfo.x;\n"
                // 240, not 255: vanilla's lightmap tops out at 240, and packs divide by that. Complementary's
                // GetLightMapCoordinates does it as (raw/256 + 1/32 - 1/32) * 32/30, which is exactly sky/240.
                + "    iris_skyLightProbe = float(skyLight) / 240.0;\n"
                + "    iris_AlphaCutoff = _IRIS_ALPHA_CUTOFF[int((lightData >> 1u) & 3u)];\n"
                + "    iris_nanFlag = 0.0;\n"
                + hoistedAssignments
                + "    irisMain();\n"
                // Robustness guard for a non-finite clip position out of the pack's vertex math.
                //
                // This used to collapse the vertex to vec4(0, 0, 2, 1) — behind the far plane, so the triangle clipped
                // away. That fixed the original symptom (one flung vertex dragging a sliver "grass spike" across the
                // screen) but created a far worse one: when the cause is a *uniform* rather than one bad vertex, every
                // terrain vertex in the frame is non-finite, so this deleted the entire world. Both terrain passes
                // carry this guard and the entity programs do not, which is exactly the recurring "world unloads
                // randomly, signs and armour stands keep drawing" report. It also made itself undiagnosable: a clipped
                // vertex never reaches a fragment, so iris_nanFlag could never be observed.
                //
                // Fall back to the plain transform instead. u_ProjectionMatrix, u_ModelViewMatrix and iris_Vertex are
                // supplied by this pipeline, not by the pack, and are finite whenever the draw itself is valid — so
                // the vertex lands where it geometrically belongs. A single bad vertex still cannot spike (it gets its
                // real position), and a bad uniform now costs the pack's vertex effects for a frame instead of the
                // whole world. iris_nanFlag survives to the fragment stage, so the failure is finally observable.
                + "    if (any(isnan(gl_Position)) || any(isinf(gl_Position))) {\n"
                + "        gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * iris_Vertex;\n"
                + "        iris_nanFlag = 1.0;\n"
                + "    }\n"
                + "}\n";
    }

    /**
     * The generated {@code gl_FragData} replacement, emitted only when the pack body actually references
     * {@code gl_FragData}/{@code gl_FragColor}: the location-0 array reserves that many output locations starting at
     * 0, which collides with the named {@code layout(location = N) out} declarations Iris-native packs (photon) use
     * instead.
     * <p>
     * Sized from the driver rather than hardcoded — see {@link DrawBuffers#fragmentOutputArraySize()}. A fixed 16
     * exceeded {@code GL_MAX_DRAW_BUFFERS} and failed to link on Mesa, which is what broke the terrain override
     * (and so the whole world render) on Intel Arc.
     */
    private static String fragDataBlock() {
        return String.join("\n",
                "layout(location = 0) out vec4 iris_FragData[" + DrawBuffers.fragmentOutputArraySize() + "];",
                "#define gl_FragColor iris_FragData[0]",
                "#define gl_FragData iris_FragData",
                ""
        );
    }

    /**
     * DEBUG tint: paints any terrain/water/shadow fragment red when its vertex tripped the NaN/Inf clip guard. Opt-in
     * with {@code -Dimpetus.iris.tintNaN=true}; the guard itself remains active as a last-ditch robustness measure.
     * If the wedge turns red, the corruption is a non-finite vertex in that gbuffer pass (gbuffers_water is the prime
     * suspect); if it stays its normal colour, the geometry is finite and the artifact is real (e.g. actual water).
     */
    /**
     * The in-shader stand-in for the fixed-function alpha test, emitted with the threshold Iris uses for this pass.
     * <p>
     * Iris picks the threshold per pass ({@code SodiumPrograms}):
     * <pre>{@code
     * source.getDirectives().getAlphaTestOverride().orElse(
     *     pass == TRANSLUCENT ? NON_ZERO_ALPHA
     *   : (pass == TERRAIN_CUTOUT || pass == SHADOW_CUTOUT) ? HALF_ALPHA
     *   : AlphaTest.ALWAYS)
     * }</pre>
     * and {@code CommonTransformer} injects no discard at all when the result is {@code ALWAYS}. {@code ShaderKey}
     * agrees independently: {@code TERRAIN_SOLID = AlphaTests.OFF} (which <em>is</em> {@code ALWAYS}),
     * {@code TERRAIN_CUTOUT = HALF_ALPHA} (0.5), {@code TERRAIN_TRANSLUCENT = NON_ZERO_ALPHA} (0.0001).
     * <p>
     * This port previously injected a discard into <em>every</em> terrain and shadow fragment shader and took the
     * threshold from two bits of a per-vertex material byte
     * ({@code _IRIS_ALPHA_CUTOFF[(lightData >> 1u) & 3u]} over <code>{0.0, 0.1, 0.5, 1.0}</code>), which has no
     * counterpart in Iris. If those bits read 3 the cutoff becomes 1.0 and every fragment with alpha &lt; 1.0 is
     * discarded — camera pass and shadow pass together, entity programs untouched because they carry no such
     * discard. Finite positions, resident geometry, populated render lists, no GL error, and no world.
     * <p>
     * A constant per pass removes that failure mode outright rather than narrowing it: no input can raise the cutoff
     * any more. Cutout and translucent still need a discard here, because unlike Iris this port disables the
     * fixed-function alpha test rather than running on core profile where it does not exist.
     *
     * @param threshold the alpha below which to discard, or {@code null} for Iris's {@code ALWAYS} (emit nothing)
     */
    private static String alphaDiscard(String snippet) {
        return snippet == null ? "" : snippet;
    }

    private static final String NAN_TINT_SNIPPET =
            "true".equalsIgnoreCase(System.getProperty("impetus.iris.tintNaN", "false"))
                    ? "    if (iris_nanFlag > 0.001) { iris_FragData[0] = vec4(1.0, 0.0, 0.0, 1.0); }\n"
                    : "";

    /**
     * DEBUG tint: reports which block id actually reached {@code mc_Entity.x} — the value every OptiFine-era pack
     * switches its per-material effects on ({@code mat = int(mc_Entity.x + 0.5)}). Opt in with
     * {@code -Dimpetus.iris.tintBlockId=<pack id>}; {@code 10009} is Complementary's leaves.
     * <p>
     * The readout is four-way rather than a yes/no highlight, so a single screenshot separates every hypothesis
     * instead of confirming one and leaving the rest open:
     * <ul>
     *   <li><b>green</b> — the requested id arrived <em>and</em> this vertex has enough sky light to clear the pack's
     *       {@code NO_WAVING_INDOORS} gate. Everything the vertex stage needs is present, so a leaf that is green and
     *       still does not move is a genuine defect.</li>
     *   <li><b>yellow</b> — the id arrived but sky light is at or below {@code 0.87}, so the pack zeroes the
     *       displacement itself. Not a bug: covered/indoor foliage is supposed to stand still. This is the case that
     *       makes waving look like it "works on some but not others" in a roofed build.</li>
     *   <li><b>red</b> — {@code -1}, the sentinel {@code WorldRenderingSettings.getBlockStateId} returns for a state
     *       the pack's {@code block.properties} does not map. Plumbing fine, id resolution missed.</li>
     *   <li><b>blue</b> — {@code 0}: nothing was ever written. Either the mesher's
     *       {@code IrisTerrainProgramOverride.areShadersActive()} gate read false when the chunk was built, or no
     *       pack table was published.</li>
     *   <li><b>magenta</b> — a different id arrived: plumbing and resolution both work, the block just maps
     *       elsewhere than expected.</li>
     * </ul>
     * A fourth signal comes free: this only runs in the terrain and shadow programs, so foliage that stays its
     * <b>normal colour</b> while everything around it is tinted is not terrain at all — it is a block entity or an
     * armour-stand item model, which no terrain waving code will ever touch. On a build like MCParks, where scenery
     * is largely block entities and armour stands, that is the expected explanation for untinted greenery.
     * <p>
     * The fragment's original alpha is preserved so the cutout discard below still carves the leaf silhouette —
     * forcing {@code a = 1.0} would paint solid quads and hide which fragments are foliage at all.
     * <p>
     * Use one probe at a time: this runs after {@link #NAN_TINT_SNIPPET} and overwrites it unconditionally, and both
     * spend red on different meanings (NaN vertex here, unmapped block id there), so enabling both reads as this one.
     */
    private static final String BLOCK_ID_TINT_SNIPPET = buildBlockIdTint();

    /**
     * DEBUG tint: renders the <em>wave amplitude</em> the pack will actually apply to each vertex, rather than the
     * inputs to it. Opt in with {@code -Dimpetus.iris.tintWaveAmp=<pack id>}.
     * <p>
     * This exists for one specific report: parts of a single leaf block move while other parts of the same block stay
     * put, so the block visibly tears. Displacement is computed per vertex, and the only per-vertex term that varies
     * between two faces of one block is sky light — Complementary scales every {@code NO_WAVING_INDOORS} effect by
     * {@code clamp(lmCoord.y - 0.87, 0.0, 0.1)}, which is a hard cutoff, not a ramp to zero. Two vertices sitting on
     * the same corner but belonging to different faces can therefore land on opposite sides of it: one gets full
     * displacement, the other exactly none, and the faces separate.
     * <p>
     * Readout, flat-shaded so each face reports its own provoking vertex:
     * <ul>
     *   <li><b>red</b> — amplitude is exactly zero. This vertex is pinned and will never move.</li>
     *   <li><b>green, brightening with amplitude</b> — this vertex is displaced, brighter meaning further.</li>
     *   <li><b>dark grey</b> — some other block; ignore it.</li>
     * </ul>
     * A block showing red faces flush against green faces is the tear, caught in the act. If instead each block is
     * uniformly one colour, the cause is not this threshold and the search should move on.
     * <p>
     * Takes precedence over {@link #BLOCK_ID_TINT_SNIPPET} when both are set, since it is emitted afterwards.
     */
    private static final String WAVE_AMP_TINT_SNIPPET = buildWaveAmpTint();

    private static String buildWaveAmpTint() {
        Integer target = parseTargetId("impetus.iris.tintWaveAmp");
        if (target == null) {
            return "";
        }
        // Mirrors the pack expression exactly: clamp(lmCoord.y - 0.87, 0.0, 0.1). The * 10.0 only rescales the
        // 0..0.1 result into 0..1 so it is visible as a colour ramp; it does not change where the cutoff falls.
        return "    {\n"
                + "        float _iris_amp = clamp(iris_skyLightProbe - 0.87, 0.0, 0.1) * 10.0;\n"
                + "        vec3 _iris_ampTint;\n"
                + "        if (abs(iris_blockIdProbe - " + target + ".0) < 0.5) {\n"
                + "            _iris_ampTint = _iris_amp <= 0.0\n"
                + "                ? vec3(1.0, 0.0, 0.0)\n"
                + "                : mix(vec3(0.0, 0.25, 0.0), vec3(0.0, 1.0, 0.0), _iris_amp);\n"
                + "        } else {\n"
                + "            _iris_ampTint = vec3(0.15);\n"
                + "        }\n"
                + "        iris_FragData[0] = vec4(_iris_ampTint, iris_FragData[0].a);\n"
                + "    }\n";
    }

    /**
     * DEBUG tint: renders per-vertex sky light as discrete vanilla light levels. Opt in with
     * {@code -Dimpetus.iris.tintSkyLight=true}.
     * <p>
     * Where the other probes ask "is this vertex above the pack's threshold", this one shows the underlying field, so
     * the field can be judged rather than a boolean derived from it.
     * <p>
     * <strong>Banded hues, not a greyscale ramp, and that distinction is the whole point.</strong> This writes into
     * {@code iris_FragData[0]}, which is colortex0 — the <em>albedo</em> the pack's deferred passes then light and
     * tonemap. A greyscale value does not survive that: it comes back as {@code value * lighting * exposure}, which is
     * unreadable. The first attempt here did exactly that and produced a near-uniform white frame that measured
     * 0.11–0.91, i.e. carried no recoverable sky light at all. Saturated hues survive, because lighting scales a
     * colour without moving it around the wheel — which is why the block-id probe reads back as near-pure primaries.
     * <p>
     * Levels are the vanilla 0–15, recovered as {@code round(sky * 15)}:
     * <ul>
     *   <li><b>green</b> — 15, full sky</li>
     *   <li><b>yellow</b> — 14</li>
     *   <li><b>magenta</b> — 13 (the pack's {@code 0.87} cutoff falls between 13 and 14, so the green/yellow-to-magenta
     *       boundary is exactly where waving stops)</li>
     *   <li><b>red</b> — 12</li>
     *   <li><b>blue</b> — 11 or below, i.e. properly shaded</li>
     * </ul>
     * What to look for: under a roof, levels should <em>fall off</em> — blues and reds indoors, greens only near
     * openings. Deep interior geometry reading green means the sky light this port feeds is simply wrong, and the
     * pack's threshold is merely the thing that made it visible. A sensible falloff means the lightmap is fine.
     * <p>
     * Emitted last, so it overrides the other tints when more than one is enabled.
     */
    private static final String SKY_LIGHT_TINT_SNIPPET =
            "true".equalsIgnoreCase(System.getProperty("impetus.iris.tintSkyLight", "false"))
                    ? "    {\n"
                            + "        int _iris_lvl = int(floor(iris_skyLightProbe * 15.0 + 0.5));\n"
                            + "        vec3 _iris_skyTint;\n"
                            + "        if (_iris_lvl >= 15) _iris_skyTint = vec3(0.0, 1.0, 0.0);\n"
                            + "        else if (_iris_lvl == 14) _iris_skyTint = vec3(1.0, 1.0, 0.0);\n"
                            + "        else if (_iris_lvl == 13) _iris_skyTint = vec3(1.0, 0.0, 1.0);\n"
                            + "        else if (_iris_lvl == 12) _iris_skyTint = vec3(1.0, 0.0, 0.0);\n"
                            + "        else _iris_skyTint = vec3(0.0, 0.0, 1.0);\n"
                            + "        iris_FragData[0] = vec4(_iris_skyTint, iris_FragData[0].a);\n"
                            + "    }\n"
                    : "";

    /** {@return the block id requested by the given debug property, or null when unset or unparseable} */
    private static Integer parseTargetId(String property) {
        String requested = System.getProperty(property);
        if (requested == null || requested.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(requested.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String buildBlockIdTint() {
        Integer target = parseTargetId("impetus.iris.tintBlockId");
        if (target == null) {
            return "";
        }
        // 0.87 is the pack-side lightmap threshold this port keeps tripping over: Complementary gates every
        // NO_WAVING_INDOORS effect on clamp(lmCoord.y - 0.87, 0.0, 0.1), so a vertex at or below it gets exactly
        // zero displacement no matter how well the block id resolved.
        String skyGate = "iris_skyLightProbe > 0.87";
        return "    {\n"
                + "        vec3 _iris_probeTint;\n"
                + "        if (abs(iris_blockIdProbe - " + target + ".0) < 0.5) {\n"
                + "            _iris_probeTint = " + skyGate + " ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 1.0, 0.0);\n"
                + "        }\n"
                + "        else if (iris_blockIdProbe < -0.5) _iris_probeTint = vec3(1.0, 0.0, 0.0);\n"
                + "        else if (iris_blockIdProbe < 0.5) _iris_probeTint = vec3(0.0, 0.0, 1.0);\n"
                + "        else _iris_probeTint = vec3(1.0, 0.0, 1.0);\n"
                + "        iris_FragData[0] = vec4(_iris_probeTint, iris_FragData[0].a);\n"
                + "    }\n";
    }

    /** Fragment prologue: promote GLSL 120 fragment built-ins to 330 core outputs/keywords. */
    private static final String FRAGMENT_PROLOGUE = String.join("\n",
            "#version 330 core",
            "// ---- Impetus/Iris terrain bridge (generated) ----",
            "vec4 iris_shadow2D(sampler2DShadow s, vec3 p) { return vec4(texture(s, p)); }",
            "vec4 iris_shadow2DLod(sampler2DShadow s, vec3 p, float l) { return vec4(textureLod(s, p, l)); }",
            "const mat4 iris_LightmapTextureMatrix = mat4(",
            "    vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0),",
            "    vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0));",
            "mat4 iris_TextureMatrix[8] = mat4[8](mat4(1.0), iris_LightmapTextureMatrix,",
            "    mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0));",
            "#define gl_TextureMatrix iris_TextureMatrix",
            "in float iris_FogFragCoord;",
            "#define gl_FogFragCoord iris_FogFragCoord",
            // gl_Fog.* stand-ins: real per-frame uniforms (fed by CommonUniforms), NOT constants. See the
            // matching note in FullscreenTransformer — const 0.0/1.0/vec4(0) forces full-strength fog for
            // packs that read gl_Fog.start/end/color. Unused ones are stripped by the compiler.
            "uniform vec4 iris_FogColor;",
            "uniform float iris_FogDensity;",
            "uniform float iris_FogStart;",
            "uniform float iris_FogEnd;",
            // gl_Fog.scale is inlined as an expression by FogParameters (Iris's 1/(end-start)), so there is
            // deliberately no iris_FogScale declaration here.
            "in vec4 iris_TexCoordArr[4];",
            "#define gl_TexCoord iris_TexCoordArr",
            "flat in float iris_AlphaCutoff;",
            "in float iris_nanFlag;",   // DEBUG: >0 where the vertex stage produced a non-finite clip position
            "flat in float iris_blockIdProbe;", // DEBUG: the pack block id mc_Entity.x carried for this fragment
            "flat in float iris_skyLightProbe;", // DEBUG: this fragment's vertex sky light, normalized to 0..1
            "// ---- end generated prologue ----",
            ""
    ) + "\n";

    public static String transformVertexShader(String source) {
        String body = stripVersion(source);
        body = renameMain(body);
        body = convertVaryings(body, "out");
        body = dropAttributeStorageQualifier(body);
        body = modernizeCommon(body);
        // Pack globals initialized from uniforms are undefined under 330 (drivers may evaluate them before uniform
        // upload — zeros/NaNs); run those initializers at the top of the generated main, like GLSL 120 did.
        GlslGlobalInitHoister.Result hoist = GlslGlobalInitHoister.hoist(body);
        return VERTEX_PROLOGUE + attributeAdapterDefines(source) + hoist.body + vertexMain(hoist.hoistedAssignments);
    }

    public static String transformFragmentShader(String source) {
        return transformFragmentShader(source, DrawBuffers.DEFAULT);
    }

    public static String transformFragmentShader(String source, int[] drawBuffers) {
        return transformFragmentShader(source, drawBuffers,
                "    if (iris_FragData[0].a < iris_AlphaCutoff) { discard; }\n");
    }

    public static String transformFragmentShader(String source, int[] drawBuffers, String alphaTestSnippet) {
        String body = stripVersion(source);
        body = renameMain(body);
        body = convertVaryings(body, "in");
        body = modernizeCommon(body);
        body = DrawBuffers.rewriteFragmentOutputs(body, drawBuffers);
        GlslGlobalInitHoister.Result hoist = GlslGlobalInitHoister.hoist(body);
        String transformed = FRAGMENT_PROLOGUE + fragDataBlock() + hoist.body
                + "\nvoid main() {\n" + hoist.hoistedAssignments + "    irisMain();\n"
                + NAN_TINT_SNIPPET
                + BLOCK_ID_TINT_SNIPPET
                + WAVE_AMP_TINT_SNIPPET
                + SKY_LIGHT_TINT_SNIPPET
                + alphaDiscard(alphaTestSnippet)
                + "}\n";
        return transformed;
    }

    // ------------------------------------------------------------------ modern (#version 130+) terrain

    /**
     * The same Impetus vertex bridge, but for modern single-source dual-stage packs (Complementary). We keep the
     * attribute decode, the {@code gl_*}→Impetus {@code #define}s and the generated {@code main}, but drop every
     * transform that assumes GLSL-120 Chocapic structure: no {@code varying} conversion (the pack flips {@code in}/
     * {@code out} itself with {@code #ifdef VERTEX_SHADER}), no global hoisting, and crucially <b>no</b>
     * {@code texture}→{@code gtexture} rename — modern packs call the {@code texture()} built-in everywhere, so that
     * rename is what corrupted them. The {@code #ifdef VERTEX_SHADER}/{@code FRAGMENT_SHADER} guards and option gates are
     * left for the driver's own preprocessor (compatibility profile). {@code renameMain} still applies: it renames both
     * stages' {@code void main()} to {@code irisMain}, and only the active one survives the driver's {@code #ifdef}.
     */
    public static String transformVertexShaderModern(String source) {
        String body = stripVersion(source);
        body = renameMain(body);
        // Delete the pack's mc_Entity/mc_midTexCoord/at_tangent attribute declarations; the prologue #defines those
        // names onto its own decoded globals, so the pack's declarations would become illegal redeclarations.
        body = dropAttributeStorageQualifier(body);
        body = rewriteFogParameters(body);
        body = ModernPackTransformer.rewriteUnsignedStrictness(body);
        return compatFor(VERTEX_PROLOGUE, source) + attributeAdapterDefines(source) + body + vertexMain("");
    }

    public static String transformFragmentShaderModern(String source) {
        return transformFragmentShaderModern(source, DrawBuffers.DEFAULT);
    }

    public static String transformFragmentShaderModern(String source, int[] drawBuffers) {
        return transformFragmentShaderModern(source, drawBuffers,
                "    if (iris_FragData[0].a < iris_AlphaCutoff) { discard; }\n");
    }

    public static String transformFragmentShaderModern(String source, int[] drawBuffers, String alphaTestSnippet) {
        String body = stripVersion(source);
        body = renameMain(body);
        body = rewriteFogParameters(body);
        body = ModernPackTransformer.rewriteUnsignedStrictness(body);
        body = DrawBuffers.rewriteFragmentOutputs(body, drawBuffers);
        // Iris parity: packs that write gl_FragData/gl_FragColor (OptiFine style) get the generated output array
        // and the injected alpha test; packs using named layout(location) outputs (photon) keep their declarations
        // — the 16-array would collide with their output locations — and handle cutout discard themselves.
        boolean usesFragData = Pattern.compile("\\bgl_Frag(?:Data|Color)\\b").matcher(body).find();
        // The NaN tint has to be emitted here too, not only on the legacy path. Complementary's terrain fragment is
        // `#version 430 compatibility`, so it comes through this method — which meant `-Dimpetus.iris.tintNaN=true`
        // silently did nothing for the one pack the flag was added to diagnose. Only the gl_FragData form can be
        // tinted: a pack using named layout(location) outputs keeps its own declarations, so there is no output here
        // whose name we know.
        String transformed = compatFor(FRAGMENT_PROLOGUE, source)
                + (usesFragData ? fragDataBlock() : "")
                + body
                + (usesFragData
                        ? "\nvoid main() {\n    irisMain();\n"
                                + NAN_TINT_SNIPPET
                                + BLOCK_ID_TINT_SNIPPET
                                + WAVE_AMP_TINT_SNIPPET
                                + SKY_LIGHT_TINT_SNIPPET
                                + alphaDiscard(alphaTestSnippet)
                                + "}\n"
                        : "\nvoid main() {\n    irisMain();\n}\n");
        return transformed;
    }

    private static final Pattern DECLARED_VERSION = Pattern.compile("#version\\s+(\\d+)");

    /**
     * The compatibility version for a modern pack: never below the 330 the prologue needs, never below the pack's own
     * declaration (photon declares 400 and relies on 400 semantics like implicit int→uint conversion), and 430 when
     * the source uses image load/store (colored-lighting voxelization).
     */
    private static String compatFor(String prologue, String packBody) {
        int version = 330;
        Matcher declared = DECLARED_VERSION.matcher(packBody);
        if (declared.find()) {
            version = Math.max(version, Integer.parseInt(declared.group(1)));
        }
        if (packBody.contains("imageStore") || packBody.contains("imageLoad")
                || packBody.contains("imageAtomic")) {
            version = Math.max(version, 430);
        }
        return prologue.replaceFirst("#version 330 core", "#version " + version + " compatibility");
    }

    // ------------------------------------------------------------------ OptiFine attribute type adaptation

    private static final Pattern SPECIAL_ATTRIBUTE_DECL = Pattern.compile(
            "(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?"
                    + "(?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*"
                    + "(?:attribute|in)\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+"
                    + "(mc_Entity|mc_midTexCoord|at_tangent|at_midBlock)\\s*;");

    /**
     * The OptiFine attribute defines, adapted to the type each attribute is DECLARED with in the pack source (the
     * declarations themselves are deleted by {@code dropAttributeStorageQualifier}). OptiFine-era packs declare
     * {@code attribute vec4 mc_midTexCoord;} while Iris-native packs use the modern Iris types ({@code vec2
     * mc_midTexCoord}, {@code vec3 mc_Entity}, {@code vec3 at_midBlock}) — pointing a vec2-typed usage at our vec4
     * global is a hard compile error, so the define has to match the pack's own view of the type. Mirrors Iris's
     * SodiumTransformer.replaceMidTexCoord/replaceMCEntity dimension adaptation.
     */
    private static String attributeAdapterDefines(String packSource) {
        java.util.Map<String, String> declaredTypes = new java.util.HashMap<>();
        Matcher decl = SPECIAL_ATTRIBUTE_DECL.matcher(packSource);
        while (decl.find()) {
            declaredTypes.putIfAbsent(decl.group(2), decl.group(1));
        }
        return "#define mc_Entity " + adaptTo(declaredTypes.get("mc_Entity"), "iris_EntityFull") + "\n"
                + "#define mc_midTexCoord " + adaptTo(declaredTypes.get("mc_midTexCoord"), "iris_MidTexFull") + "\n"
                + "#define at_tangent " + adaptTo(declaredTypes.get("at_tangent"), "iris_Tangent") + "\n"
                + "#define at_midBlock " + adaptTo(declaredTypes.get("at_midBlock"), "iris_MidBlock") + "\n";
    }

    /** Narrows the vec4 bridge global to the pack's declared attribute type (absent declaration keeps vec4). */
    private static String adaptTo(String declaredType, String vec4Global) {
        if (declaredType == null) {
            return vec4Global;
        }
        switch (declaredType) {
            case "vec3":
                return "(" + vec4Global + ".xyz)";
            case "vec2":
                return "(" + vec4Global + ".xy)";
            case "float":
                return "(" + vec4Global + ".x)";
            case "int":
                return "int(" + vec4Global + ".x)";
            case "uint":
                return "uint(max(" + vec4Global + ".x, 0.0))";
            case "ivec2":
                return "ivec2(" + vec4Global + ".xy)";
            default:
                return vec4Global;
        }
    }

    private static String stripVersion(String source) {
        return VERSION.matcher(source).replaceFirst("");
    }

    /**
     * Rename the pack's {@code void main()} to {@code irisMain} so the generated {@code main} can wrap it. Must rename
     * every occurrence: include-flattened sources can contain several {@code main} definitions in mutually exclusive
     * {@code #ifdef} branches, and this rewrite runs before preprocessing.
     */
    private static String renameMain(String source) {
        return source.replaceAll("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)", "void irisMain()");
    }

    /**
     * {@code varying} → {@code out} (vertex) or {@code in} (fragment), preserving any qualifier in front of it.
     * <p>
     * The qualifier prefix is NOT optional to handle. GLSL 120 permits {@code invariant}/{@code centroid} before
     * {@code varying}, and packs additionally write {@code flat varying} — illegal by the letter of GLSL 120, but
     * NVIDIA's compatibility compiler accepts it, so packs ship it. Anchoring this pattern at {@code ^\s*varying}
     * silently skips every one of those lines, and the surviving {@code flat varying} is a hard error once the stage
     * is lifted to 330 core: {@code C7560: OpenGL does not allow 'flat' with 'varying'} plus
     * {@code C7561: OpenGL requires 'in/out' with 'flat'}. That killed miniature-shader's gbuffers_terrain (its
     * {@code flat varying float lightSourceLevel}), so terrain silently fell back to the Impetus default program and
     * the whole world rendered vanilla while every other stage used the pack.
     * <p>
     * GLSL 330 keeps the same qualifier order ({@code invariant} then interpolation then storage), so emitting the
     * captured prefix verbatim in front of {@code in}/{@code out} is correct: {@code flat varying} → {@code flat out}.
     */
    private static String convertVaryings(String source, String direction) {
        return source.replaceAll(
                "(?m)^(\\s*)((?:(?:invariant|flat|smooth|noperspective|centroid)\\s+)*)varying\\b",
                "$1$2" + direction);
    }

    /**
     * The pack declares OptiFine's extra attributes ({@code attribute vec2 mc_Entity;} etc.) which we do not yet feed;
     * strip the {@code attribute} storage qualifier so those become plain (default-zero) globals rather than illegal
     * 330-core attribute declarations. (Feeding real mc_Entity/mc_midTexCoord/at_tangent is a later pass.)
     */
    private static String dropAttributeStorageQualifier(String source) {
        // mc_Entity / mc_midTexCoord / at_tangent are now REAL attributes fed by IrisChunkVertexType; the prologue
        // #defines those names onto its own inputs, so the pack's declarations must be deleted outright (the define
        // would otherwise rewrite them into duplicate declarations of the prologue globals).
        source = source.replaceAll("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?"
                + "(?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*"
                + "(?:attribute|in)\\s+(?:(?:lowp|mediump|highp)\\s+)?\\w+\\s+"
                + "(?:mc_Entity|mc_midTexCoord|at_tangent|at_midBlock)\\b"
                + "(?:\\s*=\\s*[^;]+)?\\s*;\\s*(?://.*)?$", "");
        // Any other attribute becomes an explicitly zero-initialized global — an uninitialized global is undefined.
        source = source.replaceAll("(?m)^(\\s*)attribute\\s+(\\w+)\\s+(\\w+)\\s*;", "$1$2 $3 = $2(0.0);");
        // Fallback for forms the initializer rewrite doesn't cover (e.g. multiple declarators): just drop the keyword.
        return source.replaceAll("(?m)^(\\s*)attribute\\s+", "$1");
    }

    /** Keyword modernizations common to both stages for 330 core. */
    private static String modernizeCommon(String source) {
        source = rewriteFogParameters(source);
        source = ModernPackTransformer.rewriteUnsignedStrictness(source);
        // OptiFine's block sampler is often literally named "texture", which clashes with GLSL 330's texture() builtin.
        // Rename the standalone sampler to "gtexture" first (word-boundary avoids touching texture2D/texture2DLod),
        // then modernize the legacy sampling functions to the builtins.
        source = source.replaceAll("\\btexture\\b", "gtexture");
        source = source.replaceAll("\\btexture2DLod\\b", "textureLod");
        source = source.replaceAll("\\btexture3DLod\\b", "textureLod");
        source = source.replaceAll("\\btexture2D\\b", "texture");
        source = source.replaceAll("\\btexture3D\\b", "texture");
        // shadow2D must keep returning vec4 (packs swizzle .x/.z off it); the iris_ wrappers are in the prologues.
        source = source.replaceAll("\\bshadow2DLod\\b", "iris_shadow2DLod");
        source = source.replaceAll("\\bshadow2D\\b", "iris_shadow2D");
        return source;
    }

    private static String rewriteFogParameters(String source) {
        return FogParameters.rewrite(source);
    }
}
