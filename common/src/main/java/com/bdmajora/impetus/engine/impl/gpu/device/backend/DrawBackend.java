package com.bdmajora.impetus.engine.impl.gpu.device.backend;

import com.bdmajora.impetus.engine.impl.gl.device.RenderDevice;
import com.bdmajora.impetus.engine.impl.gpu.device.GpuDevice;
import com.bdmajora.impetus.engine.impl.gpu.device.GpuDeviceFeature;
import com.bdmajora.impetus.engine.impl.gpu.device.GraphicsBackend;

public enum DrawBackend {
    OPENGL,
    VK_MULTIDRAW,
    VK_INDIRECT;

    // For the current device
    public static DrawBackend chooseCurrent() {
        return choose(RenderDevice.INSTANCE.getGpuDevice());
    }

    // Picks the most capable backend the device supports
    public static DrawBackend choose(GpuDevice device) {
        if (device.backend() != GraphicsBackend.VULKAN) {
            return OPENGL;
        }

        if (device.supports(GpuDeviceFeature.MULTI_DRAW_DIRECT_INTERLEAVED)) {
            return VK_MULTIDRAW;
        }

        if (device.supports(GpuDeviceFeature.MULTI_DRAW_INDIRECT)) {
            return VK_INDIRECT;
        }

        throw new IllegalStateException("No Vulkan multidraw backend is supported by this device.");
    }
}
