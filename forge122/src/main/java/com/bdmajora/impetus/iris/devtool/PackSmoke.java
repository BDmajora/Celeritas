package com.bdmajora.impetus.iris.devtool;

import com.bdmajora.impetus.iris.gl.program.DrawBuffers;
import com.bdmajora.impetus.iris.gl.shader.ShaderMacros;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;
import com.bdmajora.impetus.iris.shaderpack.ProgramSet;
import com.bdmajora.impetus.iris.shaderpack.ProgramSource;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import com.bdmajora.impetus.iris.shaderpack.ShaderPackLoader;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramArrayId;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.GlslPreprocessor;
import com.bdmajora.impetus.iris.terrain.FullscreenTransformer;
import com.bdmajora.impetus.iris.terrain.ImpetusTerrainTransformer;
import com.bdmajora.impetus.iris.terrain.ModernPackTransformer;

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
            if (id == ProgramId.Terrain || id == ProgramId.Water) {
                dumpTerrain(source, macros, outDir);
            } else {
                dumpGbuffer(source, macros, outDir);
            }
        }
        for (ProgramArrayId arrayId : ProgramArrayId.values()) {
            ProgramSource[] sources = set.getArray(arrayId);
            if (sources == null) {
                continue;
            }
            for (ProgramSource source : sources) {
                if (source == null) {
                    continue;
                }
                if (!pack.getProperties().getProgramEnabled(source.getName()).orElse(Boolean.TRUE)) {
                    System.out.println("  (skipping disabled program " + source.getName() + ")");
                    continue;
                }
                dumpFullscreen(source, macros, outDir);
            }
        }
    }

    /** Mirrors {@code ShaderProgramCompiler.compile}'s string stage (gbuffers + shadow). */
    private static void dumpGbuffer(ProgramSource source, Map<String, String> macros, Path outDir) throws Exception {
        String vsh = source.getVertexSource().orElse(null);
        String fsh = source.getFragmentSource().orElse(null);
        if (vsh == null || fsh == null) {
            return;
        }
        int[] drawBuffers = DrawBuffers.sanitize(DrawBuffers.parseActive(fsh, macros), 16);
        if (ModernPackTransformer.isModernSource(fsh)) {
            vsh = ModernPackTransformer.transform(vsh);
            fsh = ModernPackTransformer.transform(fsh);
        }
        fsh = DrawBuffers.rewriteFragmentOutputs(fsh, drawBuffers);
        write(outDir, source.getName() + ".vsh",
                IrisRenderingPipeline.stabilizeShaderSource(source.getName(), applyDefines(vsh, macros)));
        write(outDir, source.getName() + ".fsh",
                IrisRenderingPipeline.stabilizeShaderSource(source.getName(), applyDefines(fsh, macros)));
    }

    /** Mirrors {@code IrisRenderingPipeline.buildCompositePass}'s string stage (deferred/composite/final + csh). */
    private static void dumpFullscreen(ProgramSource source, Map<String, String> macros, Path outDir) throws Exception {
        String vshRaw = source.getVertexSource().orElse(null);
        String fshRaw = source.getFragmentSource().orElse(null);
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
        String csh = source.getComputeSource().orElse(null);
        if (csh != null) {
            write(outDir, source.getName() + ".csh", IrisRenderingPipeline.stabilizeShaderSource(
                    source.getName(), ShaderMacros.injectDefines(csh, macros)));
        }
    }

    /** Mirrors {@code IrisTerrainProgramOverride}'s string stage (gbuffers_terrain / gbuffers_water). */
    private static void dumpTerrain(ProgramSource source, Map<String, String> macros, Path outDir) throws Exception {
        String vshSource = source.getVertexSource().orElse(null);
        String fshSource = source.getFragmentSource().orElse(null);
        if (vshSource == null || fshSource == null) {
            return;
        }
        boolean modern = ModernPackTransformer.isModernSource(fshSource);
        int[] drawBuffers = IrisRenderingPipeline.sanitizeDrawBuffers(
                source.getName(), DrawBuffers.parseActive(fshSource, macros));
        String vsh = modern
                ? ImpetusTerrainTransformer.transformVertexShaderModern(
                        IrisRenderingPipeline.stabilizeShaderSource(source.getName(),
                                ShaderMacros.injectDefines(vshSource, macros)))
                : ImpetusTerrainTransformer.transformVertexShader(vshSource);
        String fsh = modern
                ? ImpetusTerrainTransformer.transformFragmentShaderModern(
                        IrisRenderingPipeline.stabilizeShaderSource(source.getName(),
                                ShaderMacros.injectDefines(fshSource, macros)),
                        drawBuffers)
                : ImpetusTerrainTransformer.transformFragmentShader(fshSource, drawBuffers);
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
