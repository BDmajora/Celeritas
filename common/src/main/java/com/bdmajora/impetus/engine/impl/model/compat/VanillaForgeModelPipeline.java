package com.bdmajora.impetus.engine.impl.model.compat;

public enum VanillaForgeModelPipeline implements ModdedModelPipeline {
    INSTANCE;

    @Override
    public ModelPipelineBackend backend() {
        return ModelPipelineBackend.VANILLA_FORGE;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean canHandle(Object model) {
        return true;
    }
}
