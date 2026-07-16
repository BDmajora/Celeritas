package com.bdmajora.impetus.iris.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Iris configuration backed by the OptiFine-style {@code optionsshaders.txt} file in the game directory, plus the
 * {@code shaderpacks/} directory where packs live.
 * <p>
 * The single piece of persisted state that matters in Phase 1 is the selected pack name. A name of {@code (internal)}
 * (OptiFine's sentinel) or empty means "no shader pack" — i.e. Impetus should render exactly as it does today.
 * <p>
 * The game directory is injected rather than read from {@code Minecraft.getMinecraft()} so this class stays
 * Minecraft-free and unit-testable.
 */
public final class IrisConfig {
    /** OptiFine's sentinel value meaning "shaders disabled". */
    public static final String NO_PACK = "(internal)";

    private final Path shaderpacksDirectory;
    private final Path propertiesFile;

    private String shaderPackName = NO_PACK;

    public IrisConfig(Path gameDirectory) {
        this.shaderpacksDirectory = gameDirectory.resolve("shaderpacks");
        this.propertiesFile = gameDirectory.resolve("optionsshaders.txt");
    }

    public Path getShaderpacksDirectory() {
        return this.shaderpacksDirectory;
    }

    /** @return the currently selected pack name (file or folder name under {@code shaderpacks/}). */
    public String getShaderPackName() {
        return this.shaderPackName;
    }

    public void setShaderPackName(String name) {
        this.shaderPackName = (name == null || name.trim().isEmpty()) ? NO_PACK : name.trim();
    }

    /** @return {@code true} if a real pack is selected (not the {@link #NO_PACK} sentinel). */
    public boolean isShaderPackEnabled() {
        return !NO_PACK.equals(this.shaderPackName);
    }

    /** @return the resolved path to the selected pack (folder or zip), or {@code null} if shaders are disabled. */
    public Path getSelectedPackPath() {
        return isShaderPackEnabled() ? this.shaderpacksDirectory.resolve(this.shaderPackName) : null;
    }

    /**
     * Loads {@code optionsshaders.txt} if present. Missing file is not an error — it just means defaults.
     */
    public void load() throws IOException {
        if (!Files.exists(this.propertiesFile)) {
            return;
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(this.propertiesFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        setShaderPackName(properties.getProperty("shaderPack", NO_PACK));
    }

    /**
     * Persists the current selection to {@code optionsshaders.txt}, creating directories as needed.
     */
    public void save() throws IOException {
        Files.createDirectories(this.propertiesFile.getParent());
        Properties properties = new Properties();
        properties.setProperty("shaderPack", this.shaderPackName);
        try (var writer = Files.newBufferedWriter(this.propertiesFile, StandardCharsets.UTF_8)) {
            properties.store(writer, "Impetus/Iris shader configuration");
        }
    }

    /** Ensures the {@code shaderpacks/} directory exists so users have somewhere to drop packs. */
    public void ensureShaderpacksDirectory() throws IOException {
        Files.createDirectories(this.shaderpacksDirectory);
    }

    /**
     * @return the names of every selectable shader pack in {@code shaderpacks/}: sub-directories and {@code .zip}
     * files, sorted case-insensitively. Never throws — an unreadable directory yields an empty list.
     */
    public List<String> listShaderpacks() {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(this.shaderpacksDirectory)) {
            return result;
        }
        try (Stream<Path> entries = Files.list(this.shaderpacksDirectory)) {
            entries.forEach(path -> {
                String name = path.getFileName().toString();
                if (Files.isDirectory(path) || name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    result.add(name);
                }
            });
        } catch (IOException e) {
            // Best-effort listing; return whatever we managed to collect.
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return Collections.unmodifiableList(result);
    }
}
