package com.bdmajora.impetus.engine.impl.model.light;

import com.bdmajora.impetus.engine.impl.model.light.data.QuadLightData;
import com.bdmajora.impetus.engine.impl.model.quad.ModelQuadView;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;
import org.jetbrains.annotations.NotNull;

// light pipelines light model quads for any location in the world, regardless of what produced them -
// blocks, fluids or block entities
public interface LightPipeline {
    // calculates the light data for one block model quad, storing the result in out
    // x/y/z are the coordinates of the model the quad belongs to
    // cullFace is the quad's cull face and may be ModelQuadFacing#UNASSIGNED if it has none;
    // lightFace is the light face and must not be UNASSIGNED
    // shade is true when the block is shaded by ambient occlusion
    // applyAoDepthBlending computes AO for partially inset quads by blending the fully-inset and
    // non-inset results, rather than assuming fully inset the way vanilla does
    void calculate(ModelQuadView quad, int x, int y, int z, QuadLightData out, @NotNull ModelQuadFacing cullFace,
                   @NotNull ModelQuadFacing lightFace, boolean shade, boolean applyAoDepthBlending);

    // resets any cached data held by this pipeline
    default void reset() {

    }
}
