package com.bdmajora.impetus.engine.impl.model.quad;

import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFlags;

// Read-only view of a model quad; see ModelQuadViewMutable for mutable access
public interface ModelQuadView {
    float getX(int idx);

    float getY(int idx);

    float getZ(int idx);

    int getColor(int idx);

    float getTexU(int idx);

    float getTexV(int idx);

    // Packed lightmap coords, or zero if regular lighting should be used instead
    int getLight(int idx);

    int getFlags();

    int getColorIndex();

    Object impetus$getSprite();

    ModelQuadFacing getLightFace();

    ModelQuadFacing getNormalFace();

    int getForgeNormal(int idx);

    int getComputedFaceNormal();

    // Prefers the explicit vertex-0 normal if set, falls back to the computed face normal otherwise
    default int getModFaceNormal() {
        int normal = getForgeNormal(0);
        return normal != 0 ? normal : getComputedFaceNormal();
    }

    // -1 is the "no color" sentinel
    default boolean hasColor() {
        return this.getColorIndex() != -1;
    }

    default boolean hasAmbientOcclusion() { return true; }

    default int getVanillaLightEmission() {
        return 0;
    }
}
