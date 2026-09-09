package com.bdmajora.impetus.engine.impl.render.chunk.terrain.material;

import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.material.parameters.MaterialParameters;

import java.util.Objects;

// a material is the full configuration for how a geometry element renders, corresponding to the
// vanilla RenderType configured for a block
// the configuration is encoded alongside the rest of the vertex data, which lets several vanilla
// RenderTypes be consolidated into a single terrain render pass on the CPU, and is recovered on the
// GPU inside that pass
public final class Material {
    public final TerrainRenderPass pass;
    public final int packed;

    public final AlphaCutoffParameter alphaCutoff;
    public final boolean mipped;

    // pass is the TerrainRenderPass supplying the base configuration, alphaCutoff is the alpha level
    // below which fragments are discarded, and mipped says whether mipmapping is enabled on geometry
    // rendered with this material
    public Material(TerrainRenderPass pass, AlphaCutoffParameter alphaCutoff, boolean mipped) {
        if (alphaCutoff != AlphaCutoffParameter.ZERO && !pass.supportsFragmentDiscard()) {
            throw new IllegalArgumentException("Pass does not support fragment discard");
        }

        this.pass = pass;
        this.packed = MaterialParameters.pack(alphaCutoff, mipped);

        this.alphaCutoff = alphaCutoff;
        this.mipped = mipped;
    }

    // returns the packed representation of this material, to be encoded in vertex data
    public int bits() {
        return this.packed;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Material material = (Material) o;
        return packed == material.packed && pass.equals(material.pass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pass, packed);
    }

    @Override
    public String toString() {
        return "Material{" +
                "pass=" + pass +
                ", alphaCutoff=" + alphaCutoff +
                ", mipped=" + mipped +
                '}';
    }
}
