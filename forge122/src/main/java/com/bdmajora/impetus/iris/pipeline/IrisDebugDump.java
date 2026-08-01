package com.bdmajora.impetus.iris.pipeline;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writes the GLSL this port actually hands the driver into {@code <gameDir>/impetus_debug/}, so a pack's transformed
 * source is inspectable without a GL context. This is the port's primary diagnostic: every rendering bug traced so far
 * was found by reading these dumps and feeding them to {@code tools/glslcheck.c}, not by guessing at the pack source.
 * <p>
 * Text only. Render-target PNG dumping used to live here too; it needed a scratch FBO and a full-screen
 * {@code glReadPixels} stall per buffer, and once the composite chain was understood it never paid for itself again.
 */
public final class IrisDebugDump {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");
    private static final String DEBUG_DIR_NAME = "impetus_debug";
    private static final long MIB = 1024L * 1024L;
    // A pack writes ~2 files per stage (Sildur's alone writes 64), so the file cap has to be generous or it is
    // exhausted mid-pack and the remaining stages silently vanish. MAX_DUMP_BYTES is the real disk guard.
    private static final int MAX_DUMP_FILES =
            Integer.getInteger("impetus.iris.debugDumpMaxFiles", 256);
    private static final long MAX_DUMP_BYTES =
            Long.getLong("impetus.iris.debugDumpMaxBytes", 64L * MIB);
    /**
     * Names already warned about. Per-name rather than a single latch: a one-shot warning means the second and later
     * skipped dumps vanish without a trace, which is exactly how a budget-refused dump got mistaken for a broken pass.
     */
    private static final java.util.Set<String> budgetWarnedNames =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private IrisDebugDump() {
    }

    /** Writes transformed GLSL (or any text) into the debug directory, so driver-visible sources are inspectable. */
    public static void dumpText(String name, String content) {
        try {
            File dir = debugDir();
            File out = new File(dir, name);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (!canWriteFile(name, dir, out, bytes.length)) {
                return;
            }
            Files.write(out.toPath(), bytes);
        } catch (Exception e) {
            LOGGER.error("[Iris] Failed to dump text {}", name, e);
        }
    }

    private static File debugDir() {
        return new File(Minecraft.getMinecraft().gameDir, DEBUG_DIR_NAME);
    }

    private static boolean canWriteFile(String name, File dir, File out, long incomingBytes) {
        if (!ensureDebugDir(dir)) {
            return false;
        }
        DebugDirStats stats = scanDebugDir(dir);
        boolean existingFile = out.isFile();
        long existingBytes = existingFile ? out.length() : 0L;
        int projectedFiles = stats.files + (existingFile ? 0 : 1);
        long projectedBytes = stats.bytes - existingBytes + Math.max(0L, incomingBytes);

        if (MAX_DUMP_FILES >= 0 && projectedFiles > MAX_DUMP_FILES) {
            warnBudgetOnce(name, "{} file(s) would exceed the {} file limit", projectedFiles, MAX_DUMP_FILES);
            return false;
        }
        if (MAX_DUMP_BYTES >= 0L && projectedBytes > MAX_DUMP_BYTES) {
            warnBudgetOnce(name, "{} MiB would exceed the {} MiB limit", projectedBytes / MIB, MAX_DUMP_BYTES / MIB);
            return false;
        }
        return true;
    }

    private static boolean ensureDebugDir(File dir) {
        if (dir.isDirectory()) {
            return true;
        }
        if (dir.exists()) {
            LOGGER.warn("[Iris] Could not create debug dump directory because {} already exists", dir);
            return false;
        }
        if (!dir.mkdirs()) {
            LOGGER.warn("[Iris] Could not create debug dump directory {}", dir);
            return false;
        }
        return true;
    }

    private static DebugDirStats scanDebugDir(File dir) {
        DebugDirStats stats = new DebugDirStats();
        File[] files = dir.listFiles();
        if (files == null) {
            return stats;
        }
        for (File file : files) {
            if (file.isFile()) {
                stats.files++;
                stats.bytes += file.length();
            }
        }
        return stats;
    }

    private static void warnBudgetOnce(String name, String format, Object... args) {
        if (!budgetWarnedNames.add(name)) {
            return;
        }
        LOGGER.warn("[Iris] impetus_debug budget reached; skipping dump " + name + ": " + format, args);
    }

    private static final class DebugDirStats {
        int files;
        long bytes;
    }
}
