package com.bdmajora.impetus.engine.impl.model.compat;

public enum FrapiIndigoPipeline implements ModdedModelPipeline {
    INSTANCE;

    private static final String UNAVAILABLE_REASON = "Fabric Renderer API/Indigo is not present on this Minecraft target.";

    @Override
    public ModelPipelineBackend backend() {
        return ModelPipelineBackend.FRAPI_INDIGO;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String getUnavailableReason() {
        return UNAVAILABLE_REASON;
    }
}
