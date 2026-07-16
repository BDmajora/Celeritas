package com.bdmajora.impetus.engine.impl.model.compat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ModelPipelineRegistry {
    private static final List<ModdedModelPipeline> PIPELINES = new CopyOnWriteArrayList<>();

    static {
        register(VanillaForgeModelPipeline.INSTANCE);
        register(FrapiIndigoPipeline.INSTANCE);
    }

    private ModelPipelineRegistry() {
    }

    public static void register(ModdedModelPipeline pipeline) {
        if (!PIPELINES.contains(pipeline)) {
            PIPELINES.add(pipeline);
        }
    }

    public static List<ModdedModelPipeline> getPipelines() {
        return List.copyOf(PIPELINES);
    }

    public static Optional<ModdedModelPipeline> findAvailable(ModelPipelineBackend backend) {
        return PIPELINES.stream()
                .filter(pipeline -> pipeline.backend() == backend)
                .filter(ModdedModelPipeline::isAvailable)
                .findFirst();
    }
}
