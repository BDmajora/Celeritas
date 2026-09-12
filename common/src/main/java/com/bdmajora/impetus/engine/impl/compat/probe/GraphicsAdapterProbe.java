package com.bdmajora.impetus.engine.impl.compat.probe;

import com.bdmajora.impetus.engine.impl.compat.environment.OsKind;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

// Enumerates display adapters through operating-system facilities, needing no GL context at all
// On Linux it reads the PCI ids exposed under /sys/class/drm; on Windows it queries Win32_VideoController through
// PowerShell CIM
// Both paths are strictly best-effort. Any failure — missing tools, a sandbox, an exotic setup — degrades to an
// empty result rather than an exception, because the caller only uses this to REFINE warnings and never to gate
// rendering
public final class GraphicsAdapterProbe {
    private static final long PROCESS_TIMEOUT_SECONDS = 5;

    private GraphicsAdapterProbe() {
    }

    // Enumerates adapters via sysfs on Linux or CIM on Windows; empty elsewhere
    public static List<GraphicsAdapterInfo> probe() {
        try {
            return switch (OsKind.current()) {
                case LINUX -> probeLinuxSysFs();
                case WINDOWS -> probeWindowsCim();
                default -> Collections.emptyList();
            };
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    // Walks /sys/bus/pci for display-class devices
    private static List<GraphicsAdapterInfo> probeLinuxSysFs() {
        var results = new ArrayList<GraphicsAdapterInfo>();
        var drm = Paths.get("/sys/class/drm");

        if (!Files.isDirectory(drm)) {
            return results;
        }

        try (DirectoryStream<Path> cards = Files.newDirectoryStream(drm, "card[0-9]")) {
            for (var card : cards) {
                var device = card.resolve("device");
                var vendorId = parsePciId(readTrimmed(device.resolve("vendor")));

                if (vendorId == 0) {
                    continue;
                }

                var vendor = GraphicsVendor.fromPciVendorId(vendorId);
                var driver = readUeventValue(device.resolve("uevent"), "DRIVER");
                var deviceId = readTrimmed(device.resolve("device"));

                results.add(new GraphicsAdapterInfo(vendor,
                        String.format("PCI %04x:%s", vendorId, deviceId.replace("0x", "")),
                        driver));
            }
        } catch (Exception e) {
            // Whatever was collected before the failure is still usable; an adapter list is a hint, not a
            // requirement, so a partial result beats none
        }

        return results;
    }

    // Queries Win32_VideoController through PowerShell
    private static List<GraphicsAdapterInfo> probeWindowsCim() {
        // AdapterCompatibility|Name|DriverVersion, one adapter per line.
        var lines = runProcess(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_VideoController | ForEach-Object { $_.AdapterCompatibility + '|' + $_.Name + '|' + $_.DriverVersion }");

        var results = new ArrayList<GraphicsAdapterInfo>();

        for (var line : lines) {
            var parts = line.split("\\|", 3);

            if (parts.length != 3 || parts[1].isEmpty()) {
                continue;
            }

            var vendor = classifyWindowsVendor(parts[0] + " " + parts[1]);
            results.add(new GraphicsAdapterInfo(vendor, parts[1].trim(), parts[2].trim()));
        }

        return results;
    }

    // Vendor from the adapter name string
    private static GraphicsVendor classifyWindowsVendor(String description) {
        var lower = description.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("nvidia")) {
            return GraphicsVendor.NVIDIA;
        } else if (lower.contains("amd") || lower.contains("ati ") || lower.contains("radeon")) {
            return GraphicsVendor.AMD;
        } else if (lower.contains("intel")) {
            return GraphicsVendor.INTEL;
        }

        return GraphicsVendor.OTHER;
    }

    // Runs a command and captures stdout lines, with a timeout
    private static List<String> runProcess(String... command) {
        try {
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            var output = new ArrayList<String>();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        output.add(line);
                    }
                }
            }

            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Collections.emptyList();
            }

            return output;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // File contents trimmed; null on failure
    private static String readTrimmed(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    // 0x-prefixed hex to int
    private static int parsePciId(String value) {
        try {
            return Integer.decode(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // One KEY=value line from a uevent file
    private static String readUeventValue(Path uevent, String key) {
        try {
            for (var line : Files.readAllLines(uevent, StandardCharsets.UTF_8)) {
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length() + 1).trim();
                }
            }
        } catch (Exception ignored) {
        }

        return "";
    }
}
