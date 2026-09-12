package com.bdmajora.impetus.engine.impl.model.compat;

public enum ModelPipelineBackend {
    VANILLA_FORGE("Vanilla/Forge baked model pipeline"),
    FRAPI_INDIGO("FRAPI/Indigo");

    private final String displayName;

    ModelPipelineBackend(String displayName) {
        this.displayName = displayName;
    }

    // For the debug screen
    public String getDisplayName() {
        return this.displayName;
    }
}
