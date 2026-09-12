package com.bdmajora.impetus.engine.impl.model.compat;

public interface ModdedModelPipeline {
    ModelPipelineBackend backend();

    boolean isAvailable();

    // Defaults to accepting anything
    default boolean canHandle(Object model) {
        return false;
    }

    // Defaults to none
    default String getUnavailableReason() {
        return "";
    }
}
