package com.bdmajora.impetus.engine.impl.gl.device;

import com.bdmajora.impetus.engine.impl.gpu.device.GpuDevice;
import com.bdmajora.impetus.engine.impl.gl.functions.DeviceFunctions;

public interface RenderDevice {
    RenderDevice INSTANCE = new GLRenderDevice();

    CommandList createCommandList();

    // Marks the render thread as inside engine code, enabling device use
    static void enterManagedCode() {
        RenderDevice.INSTANCE.makeActive();
    }

    // Leaves engine code
    static void exitManagedCode() {
        RenderDevice.INSTANCE.makeInactive();
    }

    void makeActive();
    void makeInactive();

    DeviceFunctions getDeviceFunctions();

    GpuDevice getGpuDevice();
}
