package com.bdmajora.impetus.engine.impl.model.compat;

public enum FrapiIndigoPipeline implements ModdedModelPipeline {
    INSTANCE;

    private static final String UNAVAILABLE_REASON = "Fabric Renderer API/Indigo is not present on this Minecraft target.";

    // FRAPI
    @Override
    public ModelPipelineBackend backend() {
        return ModelPipelineBackend.FRAPI_INDIGO;
    }

    // Never on 1.12.2
    @Override
    public boolean isAvailable() {
        return false;
    }

    // For the startup log
    @Override
    public String getUnavailableReason() {
        return UNAVAILABLE_REASON;
    }
}
