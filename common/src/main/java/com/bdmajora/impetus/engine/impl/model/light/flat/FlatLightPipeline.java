package com.bdmajora.impetus.engine.impl.model.light.flat;

import lombok.RequiredArgsConstructor;
import com.bdmajora.impetus.engine.impl.model.light.DiffuseProvider;
import com.bdmajora.impetus.engine.impl.model.light.LightPipeline;
import com.bdmajora.impetus.engine.impl.model.light.data.LightDataAccess;
import com.bdmajora.impetus.engine.impl.model.light.data.QuadLightData;
import com.bdmajora.impetus.engine.impl.model.quad.ModelQuadView;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFlags;
import com.bdmajora.impetus.engine.api.util.NormI8;

import java.util.Arrays;

import static com.bdmajora.impetus.engine.impl.model.light.data.LightDataAccess.*;

// "Classic-style" lighting - just uses the light value of the block adjacent to a face
@RequiredArgsConstructor
public class FlatLightPipeline implements LightPipeline {
    private final LightDataAccess lightCache;
    private final DiffuseProvider diffuseProvider;

    // If false, always shade by light face instead of quad normal
    private final boolean useQuadNormalsForShading;

    // One light value for the whole quad, from the block in front of the face
    @Override
    public void calculate(ModelQuadView quad, int x, int y, int z, QuadLightData out, ModelQuadFacing cullFace, ModelQuadFacing lightFace, boolean shade, boolean applyAoDepthBlending) {
        int lightmap;

        if (!lightFace.isDirection()) {
            throw new IllegalStateException();
        }

        // To match vanilla behavior, use the cull face if it exists/is available
        if (cullFace.isDirection()) {
            lightmap = getOffsetLightmap(x, y, z, cullFace);
        } else {
            int flags = quad.getFlags();
            // If the face is aligned, use the light data above it
            // To match vanilla behavior, also treat the face as aligned if it is parallel and the block state is a full cube
            if ((flags & ModelQuadFlags.IS_ALIGNED) != 0 || ((flags & ModelQuadFlags.IS_PARALLEL) != 0 && unpackFC(this.lightCache.get(x, y, z)))) {
                lightmap = getOffsetLightmap(x, y, z, lightFace);
            } else {
                lightmap = getEmissiveLightmap(this.lightCache.get(x, y, z));
            }
        }

        Arrays.fill(out.lm, lightmap);

        if ((quad.getFlags() & ModelQuadFlags.IS_VANILLA_SHADED) != 0 || !this.useQuadNormalsForShading) {
            Arrays.fill(out.br, this.diffuseProvider.getDiffuse(lightFace, shade));
        } else {
            this.applySidedBrightnessFromNormals(quad, out, shade);
        }
    }

    // Per-vertex diffuse from the vertex normals
    public void applySidedBrightnessFromNormals(ModelQuadView quad, QuadLightData out, boolean shade) {
        int normal = quad.getModFaceNormal();
        Arrays.fill(out.br, this.diffuseProvider.getDiffuse(NormI8.unpackX(normal), NormI8.unpackY(normal), NormI8.unpackZ(normal), shade));
    }

    // Vanilla mixes the origin BlockState with the offset BlockPos here - doesn't make much sense but fixes
    // dark quads on light-emitting blocks behind tinted glass. LightDataAccess can't cache that inconsistent
    // combo directly, so this manually recombines origin luminance with offset block/sky light to match it.
    private int getOffsetLightmap(int x, int y, int z, ModelQuadFacing face) {
        int word = this.lightCache.get(x, y, z);

        // Check emissivity of the origin state
        if (unpackEM(word)) {
            return LightDataAccess.FULL_BRIGHT;
        }

        // Use world light values from the offset pos, but luminance from the origin pos
        int adjWord = this.lightCache.get(x, y, z, face);
        return LightDataAccess.pack(Math.max(unpackBL(adjWord), unpackLU(word)), unpackSL(adjWord));
    }
}
