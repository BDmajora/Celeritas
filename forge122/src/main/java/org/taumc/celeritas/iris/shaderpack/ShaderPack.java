package org.taumc.celeritas.iris.shaderpack;

import org.taumc.celeritas.iris.shaderpack.include.AbsolutePackPath;
import org.taumc.celeritas.iris.shaderpack.include.IncludeProcessor;
import org.taumc.celeritas.iris.shaderpack.loading.ProgramArrayId;
import org.taumc.celeritas.iris.shaderpack.loading.ProgramId;

import java.util.Collections;
import java.util.Map;

/**
 * A fully parsed shader pack: all of its GLSL files (keyed by {@link AbsolutePackPath} relative to the pack's
 * {@code shaders/} directory), the parsed {@code shaders.properties}, and the assembled {@link ProgramSet}.
 * <p>
 * Construction is Minecraft-free: it operates on an in-memory map of file contents so it can be unit-tested and so
 * pack reads (directory vs zip) are isolated in {@link ShaderPackLoader}. {@code #include} flattening happens here, up
 * front, so consumers receive ready-to-preprocess {@link ProgramSource}s.
 * <p>
 * Per-dimension program directories are supported in the common 1.12.2 form: a program declared under
 * {@code world0/} overrides the same program at the pack root. Other dimension folders are left for a later phase.
 */
public final class ShaderPack {
    /** The conventional pack-root path of the properties file, relative to {@code shaders/}. */
    public static final AbsolutePackPath PROPERTIES_PATH = AbsolutePackPath.fromAbsolutePath("/shaders.properties");
    /** Overworld override directory checked before the pack root. */
    private static final String OVERWORLD_DIR = "/world0";

    private final Map<AbsolutePackPath, String> sources;
    private final IncludeProcessor includeProcessor;
    private final ShaderProperties properties;
    private final ProgramSet baseProgramSet;

    public ShaderPack(Map<AbsolutePackPath, String> sources) {
        this.sources = Collections.unmodifiableMap(sources);
        this.includeProcessor = new IncludeProcessor(this.sources);

        String propertiesContents = this.sources.get(PROPERTIES_PATH);
        this.properties = propertiesContents != null
                ? ShaderProperties.parse(propertiesContents)
                : ShaderProperties.empty();

        this.baseProgramSet = buildProgramSet();
    }

    public ShaderProperties getProperties() {
        return this.properties;
    }

    public ProgramSet getProgramSet() {
        return this.baseProgramSet;
    }

    public Map<AbsolutePackPath, String> getSources() {
        return this.sources;
    }

    private ProgramSet buildProgramSet() {
        ProgramSet set = new ProgramSet(this.properties);

        for (ProgramId id : ProgramId.values()) {
            ProgramSource source = readProgram(id.getSourceName());
            if (source != null) {
                set.put(id, source);
            }
        }

        for (ProgramArrayId arrayId : ProgramArrayId.values()) {
            ProgramSource[] arr = new ProgramSource[arrayId.getNumPrograms()];
            boolean any = false;
            for (int i = 0; i < arr.length; i++) {
                ProgramSource source = readProgram(arrayId.getSourceName(i));
                if (source != null) {
                    arr[i] = source;
                    any = true;
                }
            }
            if (any) {
                set.putArray(arrayId, arr);
            }
        }

        return set;
    }

    /**
     * Reads and flattens a program's stages by source name, or returns {@code null} if neither a vertex nor a fragment
     * stage exists for it anywhere in the pack.
     */
    private ProgramSource readProgram(String sourceName) {
        String vertex = readStage(sourceName, "vsh");
        String fragment = readStage(sourceName, "fsh");
        if (vertex == null && fragment == null) {
            return null;
        }
        String geometry = readStage(sourceName, "gsh");
        String tessControl = readStage(sourceName, "tcs");
        String tessEval = readStage(sourceName, "tes");
        return new ProgramSource(sourceName, vertex, geometry, tessControl, tessEval, fragment);
    }

    private String readStage(String sourceName, String extension) {
        AbsolutePackPath path = locateStage(sourceName, extension);
        if (path == null) {
            return null;
        }
        return String.join("\n", this.includeProcessor.process(path));
    }

    /** Checks the overworld override directory first, then the pack root. */
    private AbsolutePackPath locateStage(String sourceName, String extension) {
        AbsolutePackPath overworld = AbsolutePackPath.fromAbsolutePath(
                OVERWORLD_DIR + "/" + sourceName + "." + extension);
        if (this.sources.containsKey(overworld)) {
            return overworld;
        }
        AbsolutePackPath root = AbsolutePackPath.fromAbsolutePath("/" + sourceName + "." + extension);
        return this.sources.containsKey(root) ? root : null;
    }
}
