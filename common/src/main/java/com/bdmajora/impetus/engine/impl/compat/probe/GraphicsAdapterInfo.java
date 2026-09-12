package com.bdmajora.impetus.engine.impl.compat.probe;

// One display adapter as the OS reports it (every adapter present, not just the GL context's); driverVersion may be empty if the probe cannot determine it
public record GraphicsAdapterInfo(GraphicsVendor vendor, String name, String driverVersion) {
}
