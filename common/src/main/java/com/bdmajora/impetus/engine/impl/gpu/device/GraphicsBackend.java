package com.bdmajora.impetus.engine.impl.gpu.device;

public enum GraphicsBackend {
    OPENGL("OpenGL"),
    VULKAN("Vulkan");

    private final String displayName;

    GraphicsBackend(String displayName) {
        this.displayName = displayName;
    }

    // For the debug screen
    public String getDisplayName() {
        return this.displayName;
    }
}
