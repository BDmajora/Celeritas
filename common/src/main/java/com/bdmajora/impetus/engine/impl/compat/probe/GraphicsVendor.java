package com.bdmajora.impetus.engine.impl.compat.probe;

import com.bdmajora.impetus.engine.impl.compat.environment.GlContextInfo;

import java.util.Locale;

/**
 * Graphics hardware/driver vendor classification. Two independent identification paths are supported:
 * PCI vendor ids (from the OS-level probe, before/without a GL context) and GL context strings.
 */
public enum GraphicsVendor {
    NVIDIA,
    AMD,
    INTEL,
    MESA,
    OTHER;

    public static GraphicsVendor fromPciVendorId(int id) {
        return switch (id) {
            case 0x10DE -> NVIDIA;
            case 0x1002, 0x1022 -> AMD;
            case 0x8086 -> INTEL;
            default -> OTHER;
        };
    }

    public static GraphicsVendor fromContext(GlContextInfo context) {
        var vendor = context.vendor().toLowerCase(Locale.ROOT);
        var renderer = context.renderer().toLowerCase(Locale.ROOT);
        var version = context.version().toLowerCase(Locale.ROOT);

        // Mesa is checked first: its drivers report the hardware vendor in GL_RENDERER but behave like Mesa,
        // which is what matters for workaround selection.
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
