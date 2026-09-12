package com.bdmajora.impetus.engine.impl.gpu.device;

import java.util.Optional;

public final class VulkanDeviceProvider {
    private static final String UNAVAILABLE_REASON = "Vulkan backend is not available on this Minecraft target.";

    private VulkanDeviceProvider() {
    }

    // Always false; Vulkan is not available on this platform
    public static boolean isAvailable() {
        return false;
    }

    // Always empty
    public static Optional<GpuDevice> probe() {
        return Optional.empty();
    }

    // For the startup log
    public static String getUnavailableReason() {
        return UNAVAILABLE_REASON;
    }
}
