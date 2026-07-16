package com.bdmajora.impetus.engine.impl.compat.workarounds;

import com.bdmajora.impetus.engine.impl.compat.environment.GlContextInfo;
import com.bdmajora.impetus.engine.impl.compat.environment.OsKind;
import com.bdmajora.impetus.engine.impl.compat.probe.GraphicsAdapterInfo;
import com.bdmajora.impetus.engine.impl.compat.probe.GraphicsVendor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry of driver/environment issues detected on the current machine. Other engine code consults
 * {@link #isActive(Issue)} to steer around known-broken driver paths.
 * <p>
 * Unlike upstream renderers that patch the process environment through native APIs, Impetus deliberately
 * limits itself to in-process behavior changes plus loud, actionable diagnostics: the legacy LWJGL2/Java 8
 * targets leave no portable way to mutate the native environment before the driver loads.
 */
public final class Workarounds {
    private static final Logger LOGGER = LogManager.getLogger("Impetus-Workarounds");

    private static final AtomicReference<Set<Issue>> ACTIVE = new AtomicReference<>(Collections.emptySet());

    private Workarounds() {
    }

    public enum Issue {
        /**
         * The NVIDIA "Threaded Optimization" feature is known to corrupt state when a second thread issues GL
         * commands. Detection-only: surfaced as a warning telling the user to disable it in the driver control
         * panel if they see crashes.
         */
        NVIDIA_THREADED_OPTIMIZATIONS,
        /**
         * Requesting a KHR_no_error context is unsafe on this driver (older Intel Windows drivers crash or
         * render incorrectly). Consumers must not request no-error contexts while this is active.
         */
        NO_ERROR_CONTEXT_UNSAFE,
        /**
         * A frame-hooking overlay (e.g. RivaTuner Statistics Server) was detected. These inject into the GL
         * frame path and are a common source of otherwise-unexplainable crashes with modified renderers.
         */
        FRAME_HOOK_OVERLAY_PRESENT
    }

    public static void init(GlContextInfo context, List<GraphicsAdapterInfo> adapters) {
        var issues = EnumSet.noneOf(Issue.class);
        var os = OsKind.current();
        var vendor = GraphicsVendor.fromContext(context);

        if (vendor == GraphicsVendor.NVIDIA && os == OsKind.WINDOWS) {
            issues.add(Issue.NVIDIA_THREADED_OPTIMIZATIONS);
        }

        if (isIntelLegacyWindowsDriver(vendor, os, adapters)) {
            issues.add(Issue.NO_ERROR_CONTEXT_UNSAFE);
        }

        ACTIVE.set(Collections.unmodifiableSet(issues));

        if (!issues.isEmpty()) {
            LOGGER.warn("Detected environment issues, mitigations/diagnostics enabled: {}", issues);
        }
    }

    /**
     * Marks an issue discovered after {@link #init} completed (e.g. the asynchronous overlay scan).
     */
    public static void markActive(Issue issue) {
        Set<Issue> current;
        Set<Issue> updated;

        do {
            current = ACTIVE.get();

            if (current.contains(issue)) {
                return;
            }

            updated = EnumSet.noneOf(Issue.class);
            updated.addAll(current);
            updated.add(issue);
        } while (!ACTIVE.compareAndSet(current, Collections.unmodifiableSet(updated)));
    }

    public static boolean isActive(Issue issue) {
        return ACTIVE.get().contains(issue);
    }

    private static boolean isIntelLegacyWindowsDriver(GraphicsVendor vendor, OsKind os, List<GraphicsAdapterInfo> adapters) {
        if (vendor != GraphicsVendor.INTEL || os != OsKind.WINDOWS) {
            return false;
        }

        // Intel's legacy (pre-DCH, Gen7-era) Windows drivers report versions of the form 10.18.x.x or lower.
        // Those drivers ship a GL implementation that misbehaves with no-error contexts.
        for (var adapter : adapters) {
            if (adapter.vendor() != GraphicsVendor.INTEL) {
                continue;
            }

            var version = adapter.driverVersion();

            if (version.startsWith("9.") || version.startsWith("10.")) {
                return true;
            }
        }

        // Without OS driver info, err on the safe side for Intel + Windows: no-error contexts gain little and
        // the failure mode (hard crash in the driver) is much worse than the win.
        return adapters.isEmpty();
    }
}
