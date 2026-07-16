package com.bdmajora.impetus.engine.impl.compat.probe;

/**
 * One display adapter as reported by the operating system (not by GL). {@code driverVersion} is the raw
 * OS-reported string and may be empty when the platform probe cannot determine it.
 */
public record GraphicsAdapterInfo(GraphicsVendor vendor, String name, String driverVersion) {
}
