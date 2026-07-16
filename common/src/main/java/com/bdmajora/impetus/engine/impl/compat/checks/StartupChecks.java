package com.bdmajora.impetus.engine.impl.compat.checks;

import com.bdmajora.impetus.engine.impl.compat.environment.GlContextInfo;
import com.bdmajora.impetus.engine.impl.compat.environment.OsKind;
import com.bdmajora.impetus.engine.impl.compat.platform.MessageBoxUtil;
import com.bdmajora.impetus.engine.impl.compat.probe.GraphicsAdapterProbe;
import com.bdmajora.impetus.engine.impl.compat.workarounds.Workarounds;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the platform-compatibility startup sequence:
 * <ol>
 *     <li>{@link GlContextInfo#capture()} is done by the caller on the render thread (cheap, needs the context);</li>
 *     <li>everything else — OS adapter probe, workaround selection, overlay scan — runs on a daemon thread so
 *     process spawns and file reads never lengthen startup.</li>
 * </ol>
 * All failures degrade to "no diagnostics" — this layer must never be able to break launch.
 */
public final class StartupChecks {
    private static final Logger LOGGER = LogManager.getLogger("Impetus");

    private StartupChecks() {
    }

    public static void runAsync(GlContextInfo context) {
        var thread = new Thread(() -> run(context), "Impetus Compatibility Checks");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(GlContextInfo context) {
        try {
            LOGGER.info("OpenGL context: {} / {} / {}", context.vendor(), context.renderer(), context.version());

            var adapters = GraphicsAdapterProbe.probe();

            for (var adapter : adapters) {
                LOGGER.info("Display adapter: {} ({}, driver {})", adapter.name(), adapter.vendor(),
                        adapter.driverVersion().isEmpty() ? "unknown" : adapter.driverVersion());
            }

            Workarounds.init(context, adapters);
            scanForFrameHookOverlays();
        } catch (Throwable t) {
            LOGGER.debug("Startup compatibility checks failed", t);
        }
    }

    /**
     * Detects frame-hooking overlay software that injects into the GL presentation path. Impetus replaces
     * enough of the render loop that these are a leading cause of "crashes only on my machine" reports, so
     * being loud about them up front short-circuits a lot of debugging.
     */
    private static void scanForFrameHookOverlays() {
        if (OsKind.current() != OsKind.WINDOWS) {
            return;
        }

        try {
            var process = new ProcessBuilder("tasklist", "/fo", "csv", "/nh")
                    .redirectErrorStream(true)
                    .start();

            boolean rtssFound = false;

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase(Locale.ROOT).contains("rtss")) {
                        rtssFound = true;
                    }
                }
            }

            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return;
            }

            if (rtssFound) {
                Workarounds.markActive(Workarounds.Issue.FRAME_HOOK_OVERLAY_PRESENT);
                MessageBoxUtil.showWarning("Impetus — Incompatible software detected",
                        "RivaTuner Statistics Server (RTSS) appears to be running.\n\n" +
                        "RTSS hooks the OpenGL frame path and is known to cause crashes and rendering\n" +
                        "corruption with modified renderers such as Impetus. If the game crashes or\n" +
                        "renders incorrectly, close RTSS (and overlays built on it, e.g. MSI Afterburner's\n" +
                        "on-screen display) and try again.");
            }
        } catch (Exception e) {
            LOGGER.debug("Overlay scan failed", e);
        }
    }
}
