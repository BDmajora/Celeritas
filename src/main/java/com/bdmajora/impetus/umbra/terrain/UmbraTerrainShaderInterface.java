package com.bdmajora.impetus.umbra.terrain;

import com.bdmajora.impetus.engine.impl.gl.shader.ShaderBindingContext;
import com.bdmajora.impetus.engine.impl.gl.shader.uniform.GlUniformFloat3v;
import com.bdmajora.impetus.engine.impl.gl.shader.uniform.GlUniformInt;
import com.bdmajora.impetus.engine.impl.gl.shader.uniform.GlUniformMatrix4f;
import com.bdmajora.impetus.engine.impl.gl.tessellation.GlPrimitiveType;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderInterface;
import com.bdmajora.impetus.engine.impl.render.chunk.shader.ChunkShaderTextureSlot;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.GlStateManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState;
import com.bdmajora.impetus.umbra.gl.blending.ProgramAlphaTest;
import com.bdmajora.impetus.umbra.gl.blending.ProgramBlendState;
import com.bdmajora.impetus.umbra.gl.program.DrawBuffers;
import com.bdmajora.impetus.umbra.gl.program.ProgramUniforms;
import com.bdmajora.impetus.umbra.pipeline.UmbraShadowRenderer;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;

import java.util.EnumMap;
import java.util.Map;

// The ChunkShaderInterface for a shader-pack terrain program, i.e. the transformed gbuffers_terrain
// Two jobs. It feeds the engine's own per-draw state — u_ModelViewMatrix, u_ProjectionMatrix, u_RegionOffset and
// the block and lightmap sampler units — which the pack's shader never declares but the generated prologue does
// And it binds the pipeline's gbuffer framebuffer, so terrain lands in the gbuffer for the deferred chain to
// consume rather than straight into the main framebuffer
public class UmbraTerrainShaderInterface implements ChunkShaderInterface {
    private final GlUniformMatrix4f uModelViewMatrix;
    private final GlUniformMatrix4f uProjectionMatrix;
    private final GlUniformFloat3v uRegionOffset;
    private final Map<ChunkShaderTextureSlot, GlUniformInt> uTextures = new EnumMap<>(ChunkShaderTextureSlot.class);
    // The program's sanitised DRAWBUFFERS mask, re-applied to the gbuffer FBO on every bind — sanitised because a
    // pack can name a buffer this driver does not have, and an out-of-range slot makes the whole draw fail
    private final int[] drawBuffers;
    private final ProgramBlendState blendState;
    private final ProgramAlphaTest alphaTest;
    // The pack's OptiFine uniform set — gbufferModelView and its inverse, cameraPosition, the time counters —
    // uploaded on every bind
    // Not optional: a pack's world-space round trip goes through gbufferModelViewInverse, and without these the
    // multiply is against an all-zero matrix, so every terrain vertex collapses to the origin and the world
    // disappears
    // Attached after link by the program override, which is why it is not final
    private ProgramUniforms uniforms;

    private GlPrimitiveType primitiveType = GlPrimitiveType.TRIANGLES;
    private boolean restoreAfterDraw;
    private int activeDrawBufferSlots;

    public UmbraTerrainShaderInterface(ShaderBindingContext context, int[] drawBuffers, ProgramBlendState blendState,
                                      ProgramAlphaTest alphaTest) {
        this.drawBuffers = drawBuffers == null ? DrawBuffers.DEFAULT.clone() : drawBuffers.clone();
        this.blendState = blendState;
        this.alphaTest = alphaTest;
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

    public void setUniforms(ProgramUniforms uniforms) {
        this.uniforms = uniforms;
    }

    @Override
    public void setupState(TerrainRenderPass pass) {
        this.primitiveType = pass.primitiveType() == QuadPrimitiveType.DIRECT
                ? GlPrimitiveType.QUADS : GlPrimitiveType.TRIANGLES;
        // Terrain draws into the gbuffer bound by the frame pipeline; point its draw-buffer mask at this program's
        // DRAWBUFFERS so iris_FragData[k] lands in the colortex the pack asked for. (Skipped during the shadow pass —
        // onTerrainDraw would rebind the gbuffer over the shadow framebuffer.)
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        boolean shadowPass = UmbraShadowRenderer.isShadowPass();
        if (pipeline != null && !shadowPass) {
            boolean translucentPass = pass.isReverseOrder();
            if (translucentPass) {
                restoreOptifineWaterState();
            }
            pipeline.onTerrainDraw(this.drawBuffers, this.blendState, this.alphaTest, translucentPass);
            this.restoreAfterDraw = true;
            this.activeDrawBufferSlots = this.drawBuffers.length;
        }
        if (pipeline != null) {
            pipeline.bindCustomImages();
        }
        // ShaderChunkRenderer.begin binds the program before setupState, so uniform uploads land on this program.
        if (this.uniforms != null) {
            this.uniforms.update();
        }
    }

    @Override
    public void restoreState() {
        if (!this.restoreAfterDraw) {
            return;
        }
        this.restoreAfterDraw = false;
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.afterTerrainDraw(this.activeDrawBufferSlots);
        }
    }

    private static void restoreOptifineWaterState() {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.depthMask(true);
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
