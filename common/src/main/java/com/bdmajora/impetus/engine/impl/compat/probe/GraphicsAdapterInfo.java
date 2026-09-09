package com.bdmajora.impetus.engine.impl.compat.probe;

// One display adapter as the OPERATING SYSTEM reports it, not as GL does
// The distinction matters: GL only ever describes the adapter the context was created on, while this enumerates
// every adapter present — which is what catches a laptop rendering on the wrong GPU
// driverVersion is the raw OS-reported string and may be empty when the platform probe cannot determine it
public record GraphicsAdapterInfo(GraphicsVendor vendor, String name, String driverVersion) {
}
