package com.bdmajora.impetus.engine.impl.compat.probe;

import com.bdmajora.impetus.engine.impl.compat.environment.GlContextInfo;

import java.util.Locale;

// Vendor classification fed by two paths at different times: PCI ids from the OS probe before any GL context, then the GL strings once one exists
public enum GraphicsVendor {
    NVIDIA,
    AMD,
    INTEL,
    MESA,
    OTHER;

    // PCI vendor id to enum
    public static GraphicsVendor fromPciVendorId(int id) {
        return switch (id) {
            case 0x10DE -> NVIDIA;
            case 0x1002, 0x1022 -> AMD;
            case 0x8086 -> INTEL;
            default -> OTHER;
        };
    }

    // From the GL vendor string when no PCI id is available
    public static GraphicsVendor fromContext(GlContextInfo context) {
        var vendor = context.vendor().toLowerCase(Locale.ROOT);
        var renderer = context.renderer().toLowerCase(Locale.ROOT);
        var version = context.version().toLowerCase(Locale.ROOT);

        // Mesa first: its drivers report the hardware vendor in GL_RENDERER but behave like Mesa, which is what matters for workarounds
        if (version.contains("mesa") || renderer.contains("mesa") || vendor.contains("x.org")) {
            return MESA;
        } else if (vendor.contains("nvidia")) {
            return NVIDIA;
        } else if (vendor.contains("ati") || vendor.contains("amd") || vendor.contains("advanced micro devices")) {
            return AMD;
        } else if (vendor.contains("intel")) {
            return INTEL;
        }

        return OTHER;
    }
}
