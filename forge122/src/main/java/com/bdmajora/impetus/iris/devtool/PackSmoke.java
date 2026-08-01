package com.bdmajora.impetus.iris.devtool;

import com.bdmajora.impetus.iris.gl.program.DrawBuffers;
import com.bdmajora.impetus.iris.gl.program.ShaderProgramCompiler;
import com.bdmajora.impetus.iris.gl.shader.ShaderMacros;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import com.bdmajora.impetus.iris.shaderpack.ProgramSet;
import com.bdmajora.impetus.iris.shaderpack.ProgramSource;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import com.bdmajora.impetus.iris.shaderpack.ShaderPackLoader;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramArrayId;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.GlslPreprocessor;
import com.bdmajora.impetus.iris.shaderpack.texture.CustomTextureTransformer;
import com.bdmajora.impetus.iris.shaderpack.texture.TextureStage;
import com.bdmajora.impetus.iris.terrain.FullscreenTransformer;
import com.bdmajora.impetus.iris.terrain.ImpetusTerrainTransformer;
import com.bdmajora.impetus.iris.terrain.ModernPackTransformer;
import com.bdmajora.impetus.iris.terrain.VanillaNameTransformer;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless smoke tool: runs the real pack-loading + source-patching pipeline over every shader pack zip in a
 * directory and dumps the final GLSL that would be handed to the driver, without needing a GL context or a running
 * game. The dumps are meant to be fed to an external preprocessor/compiler check (macro redefinition, duplicate
 * {@code main}, stage bleed) so shader-pack loading regressions are caught before an in-game test.
 * <p>
 * Usage: {@code PackSmoke <shaderpacks-dir> <output-dir>}. Output layout: {@code <out>/<pack>/<program>.<ext>}.
 * Mirrors the three string-patching families the pipeline uses: gbuffers/shadow ({@code ShaderProgramCompiler}),
 * fullscreen composite/deferred/final ({@code IrisRenderingPipeline}), and the terrain override
 * ({@code IrisTerrainProgramOverride}); compute passes get the plain inject+stabilize treatment.
 */
public final class PackSmoke {
    private PackSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path packsDir = Paths.get(args[0]);
        Path outRoot = Paths.get(args[1]);
        List<Path> zips = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packsDir, "*.zip")) {
            stream.forEach(zips::add);
        }
        zips.sort(null);
        int failures = 0;
        for (Path zip : zips) {
            String packName = zip.getFileName().toString().replaceAll("\\.zip$", "");
            System.out.println("=== PACK " + packName);
            try {
                ShaderPack pack = ShaderPackLoader.loadFromZip(zip);
                Path outDir = outRoot.resolve(packName);
                Files.createDirectories(outDir);
                dumpPack(pack, outDir);
            } catch (Throwable t) {
                failures++;
                System.out.println("!!! LOAD FAILED " + packName + ": " + t);
                t.printStackTrace(System.out);
            }
        }
        System.out.println("=== DONE, load failures: " + failures);
    }

    private static void dumpPack(ShaderPack pack, Path outDir) throws Exception {
        Map<String, String> macros = pack.getEnvironmentDefines();
        ProgramSet set = pack.getProgramSet();

        for (ProgramId id : ProgramId.values()) {
            ProgramSource source = set.get(id).orElse(null);
            if (source == null) {
                continue;
            }
            if (!pack.getProperties().getProgramEnabled(source.getName()).orElse(Boolean.TRUE)) {
                System.out.println("  (skipping disabled program " + source.getName() + ")");
                continue;
            }
            // Per-program macro scoping, exactly as the compile paths do it: a program the pack guards with
            // !defined(IS_IRIS) compiles without the Iris identity macros.
            Map<String, String> scoped = ShaderMacros.forProgram(macros, source.getName());
            if (id == ProgramId.Terrain || id == ProgramId.Water) {
                dumpTerrain(source, scoped, outDir);
            } else if (id == ProgramId.Final) {
                // `final` is a full-screen quad program, not a gbuffer one — it goes through the composite path.
                dumpFullscreen(source, TextureStage.COMPOSITE_AND_FINAL, scoped, outDir);
            } else {
                dumpGbuffer(source, scoped, outDir);
            }
        }
        for (ProgramArrayId arrayId : ProgramArrayId.values()) {
            ProgramSource[] sources = set.getArray(arrayId);
            if (sources == null) {
                continue;
            }
            TextureStage stage = textureStageOf(arrayId);
            for (ProgramSource source : sources) {
                if (source == null) {
                    continue;
                }
                if (!pack.getProperties().getProgramEnabled(source.getName()).orElse(Boolean.TRUE)) {
                    System.out.println("  (skipping disabled program " + source.getName() + ")");
                    continue;
                }
                dumpFullscreen(source, stage, ShaderMacros.forProgram(macros, source.getName()), outDir);
            }
        }
    }

    private static TextureStage textureStageOf(ProgramArrayId arrayId) {
        switch (arrayId) {
            case Setup:
                return TextureStage.SETUP;
            case Begin:
                return TextureStage.BEGIN;
            case Prepare:
                return TextureStage.PREPARE;
            case ShadowComposite:
                return TextureStage.SHADOWCOMP;
            case Deferred:
                return TextureStage.DEFERRED;
            default:
                return TextureStage.COMPOSITE_AND_FINAL;
        }
    }

    /** Runs {@code ShaderProgramCompiler}'s string stage verbatim (gbuffers + shadow), stopping short of the GL calls. */
    private static void dumpGbuffer(ProgramSource source, Map<String, String> macros, Path outDir) throws Exception {
        if (!source.hasRasterStages()) {
            return;
        }
        ShaderProgramCompiler.PatchedSource patched =
                ShaderProgramCompiler.patchSource(source.getName(), source, macros);
        write(outDir, source.getName() + ".vsh", patched.vertex);
        write(outDir, source.getName() + ".fsh", patched.fragment);
        if (patched.geometry != null) {
            write(outDir, source.getName() + ".gsh", patched.geometry);
        }
    }

    /** Mirrors {@code IrisRenderingPipeline.buildCompositePass}'s string stage (deferred/composite/final + csh). */
    private static void dumpFullscreen(ProgramSource source, TextureStage stage, Map<String, String> macros,
                                       Path outDir) throws Exception {
        String vshRaw = CustomTextureTransformer.transform(
                source.getName(), source.getVertexSource().orElse(null), stage);
        String fshRaw = CustomTextureTransformer.transform(
                source.getName(), source.getFragmentSource().orElse(null), stage);
        vshRaw = VanillaNameTransformer.transform(vshRaw);
        fshRaw = VanillaNameTransformer.transform(fshRaw);
        if (vshRaw != null && fshRaw != null) {
            int[] drawBuffers = DrawBuffers.parseActive(fshRaw, macros);
            String vsh;
            String fsh;
            if (ModernPackTransformer.isModernSource(fshRaw)) {
                vsh = ModernPackTransformer.transform(IrisRenderingPipeline.stabilizeShaderSource(
                        source.getName(), ShaderMacros.injectDefines(vshRaw, macros)));
                fsh = DrawBuffers.rewriteFragmentOutputs(ModernPackTransformer.transform(
                        IrisRenderingPipeline.stabilizeShaderSource(
                                source.getName(), ShaderMacros.injectDefines(fshRaw, macros))),
                        drawBuffers);
            } else {
                vsh = FullscreenTransformer.transformVertexShader(vshRaw);
                fsh = FullscreenTransformer.transformFragmentShader(fshRaw, drawBuffers);
            }
            write(outDir, source.getName() + ".vsh", vsh);
            write(outDir, source.getName() + ".fsh", fsh);
        }
        String[] computes = source.getComputeSources();
        for (int variant = 0; variant < computes.length; variant++) {
            if (computes[variant] == null) {
                continue;
            }
            String name = ProgramSource.computeVariantName(source.getName(), variant);
            write(outDir, name + ".csh", IrisRenderingPipeline.stabilizeShaderSource(
                    name, ShaderMacros.injectDefines(
                            CustomTextureTransformer.transform(name, computes[variant], stage), macros)));
        }
    }

    /** Mirrors {@code IrisTerrainProgramOverride}'s string stage (gbuffers_terrain / gbuffers_water). */
    private static void dumpTerrain(ProgramSource source, Map<String, String> macros, Path outDir) throws Exception {
        String vshSource = CustomTextureTransformer.transform(
                source.getName(), source.getVertexSource().orElse(null), TextureStage.GBUFFERS_AND_SHADOW);
        String fshSource = CustomTextureTransformer.transform(
                source.getName(), source.getFragmentSource().orElse(null), TextureStage.GBUFFERS_AND_SHADOW);
        if (vshSource == null || fshSource == null) {
            return;
        }
        vshSource = VanillaNameTransformer.transform(vshSource);
        fshSource = VanillaNameTransformer.transform(fshSource);
        boolean modern = ModernPackTransformer.isModernSource(fshSource);
        int[] drawBuffers = IrisRenderingPipeline.sanitizeDrawBuffers(
                source.getName(), DrawBuffers.parseActive(fshSource, macros));
        String vsh = modern
                ? ImpetusTerrainTransformer.transformVertexShaderModern(
                        IrisRenderingPipeline.stabilizeShaderSource(source.getName(),
                                ShaderMacros.injectDefines(vshSource, macros)))
                // The 120 path injects AFTER the 330 rewrite, like IrisTerrainProgramOverride does — the layout
                // parseActive computed above and the branch the driver compiles must agree.
                : ShaderMacros.injectDefines(ImpetusTerrainTransformer.transformVertexShader(vshSource), macros);
        String fsh = modern
                ? ImpetusTerrainTransformer.transformFragmentShaderModern(
                        IrisRenderingPipeline.stabilizeShaderSource(source.getName(),
                                ShaderMacros.injectDefines(fshSource, macros)),
                        drawBuffers)
                : ShaderMacros.injectDefines(
                        ImpetusTerrainTransformer.transformFragmentShader(fshSource, drawBuffers), macros);
        System.out.println("    " + source.getName() + " resolved DRAWBUFFERS " + Arrays.toString(drawBuffers));
        write(outDir, source.getName() + ".vsh", vsh);
        write(outDir, source.getName() + ".fsh", fsh);
    }

    private static String applyDefines(String source, Map<String, String> defines) {
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        return String.join("\n", GlslPreprocessor.injectDefines(lines, defines));
    }

    private static void write(Path outDir, String fileName, String contents) throws Exception {
        Files.write(outDir.resolve(fileName), contents.getBytes(StandardCharsets.UTF_8));
    }
}
