/**
 * Umbra-style OptiFine shader-pack support for Impetus on Minecraft 1.12.2.
 *
 * <h2>Status: Phase 1 (Foundation) — shader-pack loading &amp; parsing</h2>
 *
 * This package currently implements the Minecraft-free foundation of the shader pipeline. It loads a pack from
 * {@code shaderpacks/} (folder or {@code .zip}), parses {@code shaders.properties}, flattens {@code #include}
 * directives, resolves the OptiFine program fallback chain, and exposes the result as a queryable model. <b>No
 * rendering behavior changes yet</b>: with no pack selected (or on any load failure) Impetus renders exactly as it
 * does without this module. That zero-cost-when-disabled property is a hard requirement of the port.
 *
 * <h3>Implemented</h3>
 * <ul>
 *   <li>{@link com.bdmajora.impetus.umbra.shaderpack.ShaderPackLoader} — folder/zip reading (java.nio/zip only).</li>
 *   <li>{@link com.bdmajora.impetus.umbra.shaderpack.ShaderPack} / {@code ProgramSet} / {@code ProgramSource} — the
 *       parsed model, with {@code world0/} override support and OptiFine fallback resolution
 *       ({@code gbuffers_terrain -> gbuffers_textured_lit -> gbuffers_textured -> gbuffers_basic}).</li>
 *   <li>{@link com.bdmajora.impetus.umbra.shaderpack.ShaderProperties} — {@code shaders.properties} parser.</li>
 *   <li>{@link com.bdmajora.impetus.umbra.shaderpack.include.IncludeProcessor} — recursive {@code #include} flattening
 *       with cycle detection and OptiFine relative/absolute path rules.</li>
 *   <li>{@link com.bdmajora.impetus.umbra.shaderpack.preprocessor.GlslPreprocessor} — {@code #version} detection and
 *       {@code #define} injection.</li>
 *   <li>{@link com.bdmajora.impetus.umbra.config.UmbraConfig} — {@code optionsshaders.txt} read/write.</li>
 *   <li>{@link com.bdmajora.impetus.umbra.Umbra} — orchestrator wired into the {@code @Mod} init.</li>
 * </ul>
 *
 * <h3>Deliberately deferred to later phases</h3>
 * <ul>
 *   <li>GL program compilation/linking via the Impetus {@code com.bdmajora.impetus.lwjgl} abstraction (the
 *       {@code LWJGLService} interface must first be extended with the texture/FBO/draw entry points Umbra needs).</li>
 *   <li>Legacy fixed-function built-in substitution ({@code gl_MultiTexCoord0}, {@code gl_Color}, {@code gl_Normal}
 *       ...) — seam at {@code GlslPreprocessor#replaceLegacyBuiltins}; depends on the GL attribute bindings.</li>
 *   <li>Shader-pack option parsing ({@code // option=NAME DEFAULT=VALUE}) and the config screen.</li>
 *   <li>FBO/render-target management, the G-buffer/shadow/composite passes, vertex-format extension
 *       ({@code mc_midTexCoord}, {@code at_tangent}, {@code mc_Entity}), and normal/specular atlas stitching.</li>
 * </ul>
 *
 * <h3>Repo-specific notes (differ from the original porting brief)</h3>
 * <ul>
 *   <li>The build uses Jabel, so modern Java <em>syntax</em> ({@code var}, records, switch expressions, ...) compiles
 *       to Java 8 bytecode. But forge122 compiles with {@code --release 8}, so Java 9+ <em>library</em> APIs
 *       ({@code Set.of}, {@code List.of}, {@code Stream.toList()}, ...) are NOT available here — use Java 8
 *       equivalents (e.g. {@code Arrays.asList}, {@code Collectors.toList()}). Only the bytecode-downgraded
 *       {@code common} module may use newer library APIs.</li>
 *   <li>All GL access goes through {@code com.bdmajora.impetus.lwjgl.*} (LWJGL2/3-compatible), never raw
 *       {@code org.lwjgl.opengl.*}.</li>
 * </ul>
 */
package com.bdmajora.impetus.umbra;
