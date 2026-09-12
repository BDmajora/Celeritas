package com.bdmajora.impetus.engine.impl.model.light;

import com.bdmajora.impetus.engine.impl.model.light.data.LightDataAccess;
import com.bdmajora.impetus.engine.impl.model.light.flat.FlatLightPipeline;
import com.bdmajora.impetus.engine.impl.model.light.smooth.SmoothLightPipeline;

import java.util.EnumMap;

// holds the quad lighters that compute lightmap and brightness data for each quad
// on Forge, when the experimental light pipeline is enabled, a passthrough implementation is used
// that has Forge's QuadLighter do the lighting
// otherwise the built-in SmoothLightPipeline and FlatLightPipeline are used, which implement a
// lighting model very close to vanilla's logic but with several optimisations and visual fixes
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

    // resets the light pipelines, invalidating their caches
    // called whenever the underlying world data has changed
    public void reset() {
        for (LightPipeline pipeline : this.lighters.values()) {
            pipeline.reset();
        }
    }
}
