package org.taumc.celeritas.iris.terrain;

import com.l.ausm.impl.pipeline.PipelineContext;
import org.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformFloat3v;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformInt;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformMatrix4f;
import org.embeddedt.embeddium.impl.gl.tessellation.GlPrimitiveType;
import org.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderTextureSlot;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.joml.Matrix4fc;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@link ChunkShaderInterface} for a shader-pack terrain program (the transformed {@code gbuffers_terrain}). It feeds
 * Embeddium's per-draw state — {@code u_ModelViewMatrix}/{@code u_ProjectionMatrix}/{@code u_RegionOffset} and the
 * block/lightmap sampler units — and binds AUSM's gbuffer framebuffer so terrain is written into the pipeline's
 * gbuffer instead of the main framebuffer.
 */
public class IrisTerrainShaderInterface implements ChunkShaderInterface {
    private final GlUniformMatrix4f uModelViewMatrix;
    private final GlUniformMatrix4f uProjectionMatrix;
    private final GlUniformFloat3v uRegionOffset;
    private final Map<ChunkShaderTextureSlot, GlUniformInt> uTextures = new EnumMap<>(ChunkShaderTextureSlot.class);

    private GlPrimitiveType primitiveType = GlPrimitiveType.TRIANGLES;

    public IrisTerrainShaderInterface(ShaderBindingContext context) {
        this.uModelViewMatrix = context.bindUniformIfPresent("u_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uProjectionMatrix = context.bindUniformIfPresent("u_ProjectionMatrix", GlUniformMatrix4f::new);
        this.uRegionOffset = context.bindUniformIfPresent("u_RegionOffset", GlUniformFloat3v::new);

        GlUniformInt block = firstPresent(context, "tex", "texture", "gtexture", "gcolor");
        if (block != null) {
            this.uTextures.put(ChunkShaderTextureSlot.BLOCK, block);
        }
        GlUniformInt light = firstPresent(context, "lightmap", "texLightmap");
        if (light != null) {
            this.uTextures.put(ChunkShaderTextureSlot.LIGHT, light);
        }
    }

    private static GlUniformInt firstPresent(ShaderBindingContext context, String... names) {
        for (String name : names) {
            GlUniformInt uniform = context.bindUniformIfPresent(name, GlUniformInt::new);
            if (uniform != null) {
                return uniform;
            }
        }
        return null;
    }

    @Override
    public void setupState(TerrainRenderPass pass) {
        this.primitiveType = pass.primitiveType() == QuadPrimitiveType.DIRECT
                ? GlPrimitiveType.QUADS : GlPrimitiveType.TRIANGLES;
        // Draw terrain into the shader pipeline's gbuffer (already bound by the frame hook, re-bound here defensively).
        PipelineContext.getInstance().bindWorldFramebuffer();
    }

    @Override
    public GlPrimitiveType getPrimitiveType() {
        return this.primitiveType;
    }

    @Override
    public void setProjectionMatrix(Matrix4fc matrix) {
        if (this.uProjectionMatrix != null) {
            this.uProjectionMatrix.set(matrix);
        }
    }

    @Override
    public void setModelViewMatrix(Matrix4fc matrix) {
        if (this.uModelViewMatrix != null) {
            this.uModelViewMatrix.set(matrix);
        }
    }

    @Override
    public void setRegionOffset(float x, float y, float z) {
        if (this.uRegionOffset != null) {
            this.uRegionOffset.set(x, y, z);
        }
    }

    @Override
    public void setTextureSlot(ChunkShaderTextureSlot slot, int val) {
        GlUniformInt uniform = this.uTextures.get(slot);
        if (uniform != null) {
            uniform.setInt(val);
        }
    }
}
