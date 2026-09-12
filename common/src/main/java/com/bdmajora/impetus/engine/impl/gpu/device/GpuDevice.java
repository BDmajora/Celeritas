package com.bdmajora.impetus.engine.impl.gpu.device;

import java.util.Set;

public interface GpuDevice {
    GraphicsBackend backend();

    String vendor();

    String renderer();

    String version();

    Set<GpuDeviceFeature> supportedFeatures();

    // Feature set membership
    default boolean supports(GpuDeviceFeature feature) {
        return this.supportedFeatures().contains(feature);
    }
}
