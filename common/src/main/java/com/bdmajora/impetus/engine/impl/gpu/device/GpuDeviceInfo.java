package com.bdmajora.impetus.engine.impl.gpu.device;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record GpuDeviceInfo(GraphicsBackend backend,
                            String vendor,
                            String renderer,
                            String version,
                            Set<GpuDeviceFeature> supportedFeatures) implements GpuDevice {
    public GpuDeviceInfo {
        supportedFeatures = copyFeatures(supportedFeatures);
    }

    // Immutable copy, empty-safe
    private static Set<GpuDeviceFeature> copyFeatures(Set<GpuDeviceFeature> features) {
        if (features.isEmpty()) {
            return Collections.emptySet();
        }

        return Collections.unmodifiableSet(EnumSet.copyOf(features));
    }
}
