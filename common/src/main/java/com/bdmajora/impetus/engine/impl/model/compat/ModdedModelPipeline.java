package com.bdmajora.impetus.engine.impl.model.compat;

public interface ModdedModelPipeline {
    ModelPipelineBackend backend();

    boolean isAvailable();

    default boolean canHandle(Object model) {
        return false;
    }

    default String getUnavailableReason() {
        return "";
    }
}
