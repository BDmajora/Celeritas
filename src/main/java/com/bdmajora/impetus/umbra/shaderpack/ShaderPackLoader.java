package com.bdmajora.impetus.umbra.shaderpack;

import com.bdmajora.impetus.umbra.shaderpack.include.AbsolutePackPath;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a shader pack from disk (a folder or a {@code .zip}) into the in-memory representation consumed by
 * {@link ShaderPack}. Minecraft-free: uses only {@code java.nio} and {@code java.util.zip}.
 * <p>
 * Text stages relevant to compilation (GLSL stages, includes, {@code shaders.properties}) are read as strings.
 * Binary assets the custom-texture directives can point at ({@code .png}, raw LUT/data files, plus their
 * {@code .mcmeta} sidecars) are read as raw bytes into a separate map. All keys are made relative to the pack's
 * {@code shaders/} directory.
 */
public final class ShaderPackLoader {
    /**
     * File extensions read as raw bytes for the custom-texture directives ({@code texture.<stage>.<sampler>},
     * {@code texture.noise}, {@code customTexture.<name>}) and their {@code .mcmeta} filtering sidecars.
     * Photon and several newer packs ship 3D lookup textures as {@code .dat}; treating only PNGs as binary makes
     * those directives fail even though the assets are present in the pack.
     * <p>
     * Built with {@code Arrays.asList} rather than {@code Set.of}: forge122 compiles with {@code --release 8}, so
     * Java 9+ library APIs like {@code Set.of} are unavailable even though Jabel allows modern syntax.
     */
    private static final Set<String> BINARY_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "png", "mcmeta", "dat", "bin", "raw")));

    /**
     * Text files that are pack metadata for <em>other</em> mods rather than GLSL, and so must stay out of the source
     * map entirely.
     * <p>
     * This exists only because the source map does double duty here. {@link
     * com.bdmajora.impetus.umbra.shaderpack.option.ShaderPackOptions} scans <em>every</em> entry for
     * {@code #define}/{@code const} options, whereas Umbra only ever scans files reachable through its
     * {@code IncludeGraph}. Voxy's per-dimension {@code voxy.json} (shipped by "I Like Vanilla" and Mellow) opens with
     * lines like {@code #define OVERWORLD} / {@code #define DIMENSION_NETHER}; those names are {@code #ifdef}-tested
     * throughout the pack, so scanning the JSON would register the pack's dimension macros as user-toggleable boolean
     * options. Nothing {@code #include}s these files, so dropping them costs nothing.
     */
    private static final Set<String> NON_GLSL_TEXT_EXTENSIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("json", "md")));

    /** Guard against accidentally slurping a huge file as a string. */
    private static final long MAX_TEXT_FILE_BYTES = 8L * 1024 * 1024;

    /** Guard against accidentally slurping a huge binary asset (largest known pack LUTs are a few MB). */
    private static final long MAX_BINARY_FILE_BYTES = 32L * 1024 * 1024;

    private ShaderPackLoader() {
    }

    /**
     * Loads a pack laid out as a folder containing a {@code shaders/} subdirectory.
     */
    public static ShaderPack loadFromDirectory(Path packRoot) throws IOException {
        return loadFromDirectory(packRoot, Collections.emptyMap());
    }

    public static ShaderPack loadFromDirectory(Path packRoot, Map<String, String> changedConfigs) throws IOException {
        Path shadersDir = packRoot.resolve("shaders");
        if (!Files.isDirectory(shadersDir)) {
            throw new IOException("Shader pack has no shaders/ directory: " + packRoot);
        }

        Map<AbsolutePackPath, String> sources = new HashMap<>();
        Map<AbsolutePackPath, byte[]> binaries = new HashMap<>();
        Files.walkFileTree(shadersDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = "/" + shadersDir.relativize(file).toString().replace('\\', '/');
                // Binary first: isTextPath is now "not binary", so testing it first would starve this branch.
                if (isBinaryPath(relative) && attrs.size() <= MAX_BINARY_FILE_BYTES) {
                    binaries.put(AbsolutePackPath.fromAbsolutePath(relative), Files.readAllBytes(file));
                } else if (isTextPath(relative) && attrs.size() <= MAX_TEXT_FILE_BYTES) {
                    sources.put(AbsolutePackPath.fromAbsolutePath(relative),
                            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return new ShaderPack(sources, changedConfigs, binaries);
    }

    /**
     * Loads a pack distributed as a {@code .zip}. The archive is expected to contain a top-level {@code shaders/}
     * directory; keys are made relative to it.
     */
    public static ShaderPack loadFromZip(Path zipFile) throws IOException {
        return loadFromZip(zipFile, Collections.emptyMap());
    }

    public static ShaderPack loadFromZip(Path zipFile, Map<String, String> changedConfigs) throws IOException {
        Map<AbsolutePackPath, String> sources = new HashMap<>();
        Map<AbsolutePackPath, byte[]> binaries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                int idx = name.indexOf("shaders/");
                if (idx < 0) {
                    continue;
                }
                String relative = "/" + name.substring(idx + "shaders/".length());
                if (relative.equals("/")) {
                    continue;
                }
                // Binary first: isTextPath is now "not binary", so testing it first would starve this branch.
                if (isBinaryPath(relative)) {
                    byte[] contents = readBinaryEntry(zip);
                    if (contents != null) {
                        binaries.put(AbsolutePackPath.fromAbsolutePath(relative), contents);
                    }
                } else if (isTextPath(relative)) {
                    String contents = readEntry(zip);
                    if (contents != null) {
                        sources.put(AbsolutePackPath.fromAbsolutePath(relative), contents);
                    }
                }
                zip.closeEntry();
            }
        }

        if (sources.isEmpty()) {
            throw new IOException("Shader pack zip contained no shaders/ entries: " + zipFile);
        }
        return new ShaderPack(sources, changedConfigs, binaries);
    }

    /**
     * Everything that is not a recognized binary asset is read as text.
     * <p>
     * This must NOT be a whitelist of known GLSL extensions. Umbra never pre-scans the pack at all: its
     * {@code IncludeGraph} walks out from the program stages and reads whatever path an {@code #include} names
     * straight off disk, so the extension is irrelevant. OptiFine's {@code ShaderPackParser.resolveIncludes} does the
     * same and hard-errors when the file is missing. Because this port pre-scans into a source map instead (options
     * are applied per-file before includes are flattened), a whitelist here silently drops include files and the
     * {@code #include} then resolves to nothing — every constant it defined becomes an "undefined variable" compile
     * error and the whole pack falls back to vanilla rendering. That is exactly what happened to miniature-shader's
     * {@code /shader.h} and RedHat's 22 {@code lib/defines/*.h} files.
     *
     * @see #NON_GLSL_TEXT_EXTENSIONS for the one narrow carve-out.
     */
    private static boolean isTextPath(String relative) {
        int dot = relative.lastIndexOf('.');
        if (dot >= 0 && NON_GLSL_TEXT_EXTENSIONS.contains(relative.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return false;
        }
        return !isBinaryPath(relative);
    }

    private static boolean isBinaryPath(String relative) {
        int dot = relative.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return BINARY_EXTENSIONS.contains(relative.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String readEntry(ZipInputStream zip) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[4096];
        long total = 0;
        // Do not close the reader; it would close the shared ZipInputStream.
        BufferedReader reader = new BufferedReader(new InputStreamReader(new UncloseableStream(zip), StandardCharsets.UTF_8));
        int read;
        while ((read = reader.read(buffer)) != -1) {
            total += read;
            if (total > MAX_TEXT_FILE_BYTES) {
                return null;
            }
            sb.append(buffer, 0, read);
        }
        return sb.toString();
    }

    private static byte[] readBinaryEntry(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BINARY_FILE_BYTES) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Wraps a stream so that {@code close()} is a no-op (the underlying {@link ZipInputStream} is reused per entry). */
    private static final class UncloseableStream extends InputStream {
        private final InputStream delegate;

        UncloseableStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return this.delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return this.delegate.read(b, off, len);
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
