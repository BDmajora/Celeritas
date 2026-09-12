package com.bdmajora.impetus.engine.impl.model.compat;

public enum VanillaForgeModelPipeline implements ModdedModelPipeline {
    INSTANCE;

    // VANILLA
    @Override
    public ModelPipelineBackend backend() {
        return ModelPipelineBackend.VANILLA_FORGE;
    }

    // Always
    @Override
    public boolean isAvailable() {
        return true;
    }

    // Any baked model
    @Override
    public boolean canHandle(Object model) {
        return true;
    }
}
