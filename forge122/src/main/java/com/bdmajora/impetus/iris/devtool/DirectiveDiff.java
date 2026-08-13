package com.bdmajora.impetus.iris.devtool;

import com.bdmajora.impetus.iris.gl.shader.ShaderMacros;
import com.bdmajora.impetus.iris.gl.texture.InternalTextureFormat;
import com.bdmajora.impetus.iris.shaderpack.ProgramSet;
import com.bdmajora.impetus.iris.shaderpack.ProgramSource;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import com.bdmajora.impetus.iris.shaderpack.ShaderPackLoader;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramArrayId;
import com.bdmajora.impetus.iris.shaderpack.loading.ProgramId;
import com.bdmajora.impetus.iris.shaderpack.preprocessor.GlslPreprocessor;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headless regression check for the {@code const} directive scans: for every shader pack zip in a directory, resolves
 * the buffer directives the OLD way (regex over raw source, last textual match) and the NEW way (regex over source with
 * the pack's own {@code #if} conditionals resolved) and prints every disagreement.
 * <p>
 * Uses the real {@link ShaderPackLoader}, so includes are flattened and option values are applied in place exactly as
 * at runtime — which is what makes the conditional resolution able to see {@code #define OPTION <value>} at all.
 * <p>
 * A difference is not automatically a bug: the whole point of the fix is that the new value is the one the pack's
 * active branch declares. This tool exists to make the blast radius of the change visible and reviewable per pack,
 * and to prove packs with no conditional directives are untouched.
 * <p>
 * Usage: {@code DirectiveDiff <shaderpacks-dir>}.
 */
public final class DirectiveDiff {
    private static final Pattern FORMAT =
            Pattern.compile("const\\s+int\\s+(\\w+?)Format\\s*=\\s*(\\w+)\\s*;");
    private static final Pattern CLEAR =
            Pattern.compile("const\\s+bool\\s+(\\w+?)Clear\\s*=\\s*(true|false)\\s*;");
    private static final Pattern CLEAR_COLOR =
            Pattern.compile("const\\s+vec4\\s+(\\w+?)ClearColor\\s*=\\s*vec4\\s*\\(([^)]*)\\)\\s*;");
    private static final Pattern MIPMAP =
            Pattern.compile("const\\s+bool\\s+(\\w+?)MipmapEnabled\\s*=\\s*(true|false)\\s*;");
    private static final Pattern GAUX4 =
            Pattern.compile("/\\*\\s*GAUX4FORMAT\\s*:\\s*(\\w+)\\s*\\*/");
    /**
     * Scalars read by {@code applyPackScalarDirectives}, whose scope is every stage of every program.
     */
    private static final String[] PACK_WIDE_SCALARS = {
            "centerDepthHalflife", "noiseTextureResolution", "ambientOcclusionLevel",
            "wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife",
    };

    /**
     * Scalars read by {@code createShadowRenderer}, whose scope is deliberately narrower: the vertex+fragment stages of
     * shadow/terrain/water/final plus the fragment stage of every fullscreen pass. Compared separately because a
     * whole-pack scan would report differences that production never sees.
     */
    private static final String[] SHADOW_SCOPE_SCALARS = {
            "shadowMapResolution", "shadowDistance", "shadowDistanceRenderMul", "voxelDistance",
            "sunPathRotation", "shadowMapFov", "shadowNearPlane", "shadowFarPlane", "shadowIntervalSize",
    };

    private static final ProgramId[] SHADOW_SCOPE_PROGRAMS =
            {ProgramId.Shadow, ProgramId.Terrain, ProgramId.Water, ProgramId.Final};

    private DirectiveDiff() {
    }

    public static void main(String[] args) throws Exception {
        Path packsDir = Paths.get(args[0]);
        List<Path> zips = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packsDir, "*.zip")) {
            stream.forEach(zips::add);
        }
        zips.sort(null);

        int packsWithDiffs = 0;
        int unknownFormats = 0;
        for (Path zip : zips) {
            String name = zip.getFileName().toString().replaceAll("\\.zip$", "");
            ShaderPack pack;
            Map<String, String> config = readConfig(zip);
            try {
                pack = ShaderPackLoader.loadFromZip(zip, config);
            } catch (Throwable t) {
                System.out.println("=== " + name + "\n    !!! LOAD FAILED: " + t);
                continue;
            }

            Map<String, String> raw = new TreeMap<>();
            Map<String, String> active = new TreeMap<>();
            StringBuilder rawScalars = new StringBuilder();
            StringBuilder activeScalars = new StringBuilder();
            for (ProgramSource source : collectAll(pack)) {
                Map<String, String> macros =
                        ShaderMacros.forProgram(pack.getEnvironmentDefines(), source.getName());
                // Vertex first, fragment last: the scans are last-wins and the fragment stage is authoritative.
                for (int i = 0; i < 2; i++) {
                    boolean fragment = i == 1;
                    Optional<String> stage = fragment ? source.getFragmentSource() : source.getVertexSource();
                    if (!stage.isPresent()) {
                        continue;
                    }
                    String rawText = stage.get();
                    String activeText = GlslPreprocessor.resolveConditionals(rawText, macros);
                    scan(rawText, raw, source.getName(), fragment);
                    scan(activeText, active, source.getName(), fragment);
                    rawScalars.append(rawText).append('\n');
                    activeScalars.append(activeText).append('\n');
                }
            }
            for (String scalar : PACK_WIDE_SCALARS) {
                String before = scalarOf(rawScalars.toString(), scalar);
                String after = scalarOf(activeScalars.toString(), scalar);
                if (before != null || after != null) {
                    raw.put("scalar " + scalar, String.valueOf(before));
                    active.put("scalar " + scalar, String.valueOf(after));
                }
            }
            // Same again over the shadow path's narrower source set.
            StringBuilder rawShadow = new StringBuilder();
            StringBuilder activeShadow = new StringBuilder();
            for (ProgramId id : SHADOW_SCOPE_PROGRAMS) {
                ProgramSource source = pack.getProgramSet().get(id).orElse(null);
                if (source == null) {
                    continue;
                }
                appendShadowScope(pack, source, source.getVertexSource(), rawShadow, activeShadow);
                appendShadowScope(pack, source, source.getFragmentSource(), rawShadow, activeShadow);
            }
            for (ProgramArrayId arrayId : ProgramArrayId.values()) {
                ProgramSource[] array = pack.getProgramSet().getArray(arrayId);
                if (array == null) {
                    continue;
                }
                for (ProgramSource source : array) {
                    if (source != null) {
                        appendShadowScope(pack, source, source.getFragmentSource(), rawShadow, activeShadow);
                    }
                }
            }
            for (String scalar : SHADOW_SCOPE_SCALARS) {
                String before = scalarOf(rawShadow.toString(), scalar);
                String after = scalarOf(activeShadow.toString(), scalar);
                if (before != null || after != null) {
                    raw.put("shadow-scope " + scalar, String.valueOf(before));
                    active.put("shadow-scope " + scalar, String.valueOf(after));
                }
            }

            List<String> diffs = new ArrayList<>();
            diffs.addAll(firstVersusLast(activeScalars.toString(), PACK_WIDE_SCALARS, "scalar"));
            diffs.addAll(firstVersusLast(activeShadow.toString(), SHADOW_SCOPE_SCALARS, "shadow-scope"));
            for (String key : new TreeMap<>(mergeKeys(raw, active)).keySet()) {
                String before = raw.get(key);
                String after = active.get(key);
                if (before == null ? after != null : !before.equals(after)) {
                    diffs.add(String.format("    %-46s %s  ->  %s", key,
                            before == null ? "(absent)" : before, after == null ? "(absent)" : after));
                }
            }
            // Any format name we cannot map is a gap in InternalTextureFormat, regardless of the diff.
            List<String> unknown = new ArrayList<>();
            for (Map.Entry<String, String> entry : active.entrySet()) {
                if (entry.getKey().endsWith("Format") && !entry.getValue().equals("null")
                        && !InternalTextureFormat.fromString(entry.getValue()).isPresent()) {
                    unknown.add("    UNKNOWN FORMAT " + entry.getKey() + " = " + entry.getValue());
                }
            }
            unknownFormats += unknown.size();

            if (diffs.isEmpty() && unknown.isEmpty()) {
                System.out.println("=== " + name + "  (no change)");
            } else {
                packsWithDiffs += diffs.isEmpty() ? 0 : 1;
                System.out.println("=== " + name);
                diffs.forEach(System.out::println);
                unknown.forEach(System.out::println);
            }
        }
        System.out.println("\n=== DONE: " + packsWithDiffs + " pack(s) changed, "
                + unknownFormats + " unknown format(s)");
    }

    /** Mipmap directives are per program and fragment-only; the rest are pack-wide. */
    private static void scan(String text, Map<String, String> out, String program, boolean fragment) {
        Matcher m = FORMAT.matcher(text);
        while (m.find()) {
            out.put(m.group(1) + "Format", m.group(2));
        }
        m = CLEAR.matcher(text);
        while (m.find()) {
            out.put(m.group(1) + "Clear", m.group(2));
        }
        m = CLEAR_COLOR.matcher(text);
        while (m.find()) {
            out.put(m.group(1) + "ClearColor", "vec4(" + m.group(2).trim() + ")");
        }
        m = GAUX4.matcher(text);
        while (m.find()) {
            out.put("GAUX4FORMAT", m.group(1));
        }
        if (fragment) {
            m = MIPMAP.matcher(text);
            while (m.find()) {
                out.put(program + ": " + m.group(1) + "MipmapEnabled", m.group(2));
            }
        }
    }

    private static void appendShadowScope(ShaderPack pack, ProgramSource source, Optional<String> stage,
                                         StringBuilder raw, StringBuilder active) {
        if (!stage.isPresent()) {
            return;
        }
        raw.append(stage.get()).append('\n');
        active.append(GlslPreprocessor.resolveConditionals(stage.get(),
                ShaderMacros.forProgram(pack.getEnvironmentDefines(), source.getName()))).append('\n');
    }

    private static String scalarOf(String text, String name) {
        return scalarOf(text, name, true);
    }

    private static String scalarOf(String text, String name, boolean last) {
        Matcher m = Pattern.compile("const\\s+(?:float|int)\\s+" + name
                + "\\s*=\\s*([-+]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)[fF]?").matcher(text);
        String value = null;
        while (m.find()) {
            value = m.group(1);
            if (!last) {
                break;
            }
        }
        return value;
    }

    /**
     * Isolates the first-match-wins -> last-match-wins change, independently of conditional resolution: both columns are
     * read from the SAME resolved text. Any row here is a value that today's semantics change even for a pack whose
     * conditionals resolve identically, so it is the row that needs justifying against Iris.
     */
    private static List<String> firstVersusLast(String activeText, String[] names, String label) {
        List<String> rows = new ArrayList<>();
        for (String name : names) {
            String first = scalarOf(activeText, name, false);
            String last = scalarOf(activeText, name, true);
            if (first == null ? last != null : !first.equals(last)) {
                rows.add(String.format("    [first->last] %-32s %s  ->  %s", label + " " + name, first, last));
            }
        }
        return rows;
    }

    /**
     * The user's saved option values from {@code <pack>.zip.txt}, the same sidecar the in-game selector writes. Without
     * it the pack loads at its authored defaults, which resolves conditionals against the wrong option set — e.g.
     * Complementary defaults to {@code COLORED_LIGHTING 0} and so declares no {@code voxelDistance} at all, while the
     * user's config has 512 and does.
     */
    private static Map<String, String> readConfig(Path zip) {
        Path sidecar = zip.resolveSibling(zip.getFileName().toString() + ".txt");
        Map<String, String> config = new LinkedHashMap<>();
        if (!Files.isRegularFile(sidecar)) {
            return config;
        }
        try {
            for (String line : Files.readAllLines(sidecar)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    config.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                }
            }
        } catch (Exception e) {
            System.out.println("    (could not read " + sidecar.getFileName() + ": " + e + ")");
        }
        return config;
    }

    private static Map<String, String> mergeKeys(Map<String, String> a, Map<String, String> b) {
        Map<String, String> merged = new LinkedHashMap<>(a);
        merged.putAll(b);
        return merged;
    }

    private static List<ProgramSource> collectAll(ShaderPack pack) {
        List<ProgramSource> sources = new ArrayList<>();
        ProgramSet set = pack.getProgramSet();
        for (ProgramId id : ProgramId.values()) {
            set.get(id).ifPresent(sources::add);
        }
        for (ProgramArrayId id : ProgramArrayId.values()) {
            ProgramSource[] array = set.getArray(id);
            if (array == null) {
                continue;
            }
            for (ProgramSource source : array) {
                if (source != null) {
                    sources.add(source);
                }
            }
        }
        return sources;
    }
}
