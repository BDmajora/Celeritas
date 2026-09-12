package com.bdmajora.impetus.engine.impl.model.light;

import com.bdmajora.impetus.engine.impl.model.light.data.LightDataAccess;
import com.bdmajora.impetus.engine.impl.model.light.flat.FlatLightPipeline;
import com.bdmajora.impetus.engine.impl.model.light.smooth.SmoothLightPipeline;

import java.util.EnumMap;

// Holds the quad lighters; on Forge with the experimental pipeline a passthrough hands lighting to Forge's QuadLighter, otherwise the built-in Smooth/Flat pipelines (vanilla-like with optimisations and fixes) are used
public class LightPipelineProvider {
    private final EnumMap<LightMode, LightPipeline> lighters = new EnumMap<>(LightMode.class);
    private final LightDataAccess lightData;

    public LightPipelineProvider(LightDataAccess cache, DiffuseProvider diffuseProvider, boolean useQuadNormalsForShading) {
        this.lightData = cache;
        this.lighters.put(LightMode.SMOOTH, new SmoothLightPipeline(cache, diffuseProvider, useQuadNormalsForShading));
        this.lighters.put(LightMode.FLAT, new FlatLightPipeline(cache, diffuseProvider, useQuadNormalsForShading));
    }

    // Flat or smooth
    public LightPipeline getLighter(LightMode type) {
        LightPipeline pipeline = this.lighters.get(type);

        if (pipeline == null) {
            throw new NullPointerException("No lighter exists for mode: " + type.name());
        }

        return pipeline;
    }

    // The shared light cache both pipelines read
    public LightDataAccess getLightData() {
        return this.lightData;
    }

    // Resets the light pipelines and invalidates their caches; called whenever the underlying world data changes
    public void reset() {
        for (LightPipeline pipeline : this.lighters.values()) {
            pipeline.reset();
        }
    }
}
