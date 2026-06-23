package org.taumc.celeritas.iris.shaderpack;

import org.taumc.celeritas.iris.shaderpack.include.AbsolutePackPath;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a shader pack from disk (a folder or a {@code .zip}) into the in-memory representation consumed by
 * {@link ShaderPack}. Minecraft-free: uses only {@code java.nio} and {@code java.util.zip}.
 * <p>
 * Only text stages relevant to compilation are read (GLSL stages, includes, {@code shaders.properties}). Binary
 * assets such as {@code _n.png}/{@code _s.png} normal/specular maps are intentionally ignored here — atlas stitching
 * is a later phase. All keys are made relative to the pack's {@code shaders/} directory.
 */
public final class ShaderPackLoader {
    /** File extensions read as GLSL/text. Includes may use any of these. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "vsh", "fsh", "gsh", "tcs", "tes", "glsl", "inc", "properties", "txt");

    /** Guard against accidentally slurping a huge file as a string. */
    private static final long MAX_TEXT_FILE_BYTES = 8L * 1024 * 1024;

    private ShaderPackLoader() {
    }

    /**
     * Loads a pack laid out as a folder containing a {@code shaders/} subdirectory.
     */
    public static ShaderPack loadFromDirectory(Path packRoot) throws IOException {
        Path shadersDir = packRoot.resolve("shaders");
        if (!Files.isDirectory(shadersDir)) {
            throw new IOException("Shader pack has no shaders/ directory: " + packRoot);
        }

        Map<AbsolutePackPath, String> sources = new HashMap<>();
        Files.walkFileTree(shadersDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = "/" + shadersDir.relativize(file).toString().replace('\\', '/');
                if (isTextPath(relative) && attrs.size() <= MAX_TEXT_FILE_BYTES) {
                    sources.put(AbsolutePackPath.fromAbsolutePath(relative),
                            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return new ShaderPack(sources);
    }

    /**
     * Loads a pack distributed as a {@code .zip}. The archive is expected to contain a top-level {@code shaders/}
     * directory; keys are made relative to it.
     */
    public static ShaderPack loadFromZip(Path zipFile) throws IOException {
        Map<AbsolutePackPath, String> sources = new HashMap<>();
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
                if (relative.equals("/") || !isTextPath(relative)) {
                    continue;
                }
                String contents = readEntry(zip);
                if (contents != null) {
                    sources.put(AbsolutePackPath.fromAbsolutePath(relative), contents);
                }
                zip.closeEntry();
            }
        }

        if (sources.isEmpty()) {
            throw new IOException("Shader pack zip contained no shaders/ entries: " + zipFile);
        }
        return new ShaderPack(sources);
    }

    private static boolean isTextPath(String relative) {
        int dot = relative.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(relative.substring(dot + 1).toLowerCase(Locale.ROOT));
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
