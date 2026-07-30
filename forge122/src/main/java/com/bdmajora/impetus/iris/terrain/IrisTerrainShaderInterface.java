package com.bdmajora.impetus.iris.terrain;

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
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.uniforms.CapturedRenderingState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.bdmajora.impetus.iris.gl.blending.ProgramBlendState;
import com.bdmajora.impetus.iris.gl.program.DrawBuffers;
import com.bdmajora.impetus.iris.gl.program.ProgramUniforms;
import com.bdmajora.impetus.iris.pipeline.IrisShadowRenderer;
import com.bdmajora.impetus.iris.pipeline.IrisRenderingPipeline;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@link ChunkShaderInterface} for a shader-pack terrain program (the transformed {@code gbuffers_terrain}). It feeds
 * Impetus's per-draw state — {@code u_ModelViewMatrix}/{@code u_ProjectionMatrix}/{@code u_RegionOffset} and the
 * block/lightmap sampler units — and binds AUSM's gbuffer framebuffer so terrain is written into the pipeline's
 * gbuffer instead of the main framebuffer.
 */
public class IrisTerrainShaderInterface implements ChunkShaderInterface {
    private final GlUniformMatrix4f uModelViewMatrix;
    private final GlUniformMatrix4f uProjectionMatrix;
    private final GlUniformFloat3v uRegionOffset;
    private final Map<ChunkShaderTextureSlot, GlUniformInt> uTextures = new EnumMap<>(ChunkShaderTextureSlot.class);
    /** The program's sanitized {@code DRAWBUFFERS} mask, applied to the gbuffer FBO whenever this program binds. */
    private final int[] drawBuffers;
    private final ProgramBlendState blendState;
    /**
     * The pack's OptiFine uniform set ({@code gbufferModelView(Inverse)}, {@code cameraPosition}, time…), uploaded on
     * every bind. Without these the pack's world-space round-trip (through {@code gbufferModelViewInverse}) multiplies
     * by zero matrices and every vertex collapses to the origin. Attached after link by the program override.
     */
    private ProgramUniforms uniforms;

    private GlPrimitiveType primitiveType = GlPrimitiveType.TRIANGLES;
    private boolean restoreAfterDraw;
    private int activeDrawBufferSlots;

    // --- one-shot water SSR matrix probe (remove after diagnosis) ---
    // The water shader's screen-space reflection assumes gl_ProjectionMatrix (== our chunk u_ProjectionMatrix, which
    // builds vReflData[1]) is byte-identical to the gbufferProjection uniform (which builds the march's clipTarget).
    // If they differ, the SSR direction smears per-pixel -> the radial/diagonal blocky fan. This can't be proven
    // statically (both come from ActiveRenderInfo, but at different times/copies), so log the actual numeric delta
    // at the translucent (water) draw, a handful of times, then go quiet.
    private static final Logger PROBE_LOGGER = LogManager.getLogger("ImpetusWaterProbe");
    private static int waterProbeRemaining =
            Integer.getInteger("impetus.iris.waterMatrixProbe", 3);
    private final Matrix4f lastChunkProjection = new Matrix4f();
    private final Matrix4f lastChunkModelView = new Matrix4f();

    public IrisTerrainShaderInterface(ShaderBindingContext context, int[] drawBuffers, ProgramBlendState blendState) {
        this.drawBuffers = drawBuffers == null ? DrawBuffers.DEFAULT.clone() : drawBuffers.clone();
        this.blendState = blendState;
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
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
        boolean shadowPass = IrisShadowRenderer.isShadowPass();
        if (pipeline != null && !shadowPass) {
            boolean translucentPass = pass.isReverseOrder();
            if (translucentPass) {
                restoreOptifineWaterState();
                logWaterMatrixProbe();
            }
            pipeline.onTerrainDraw(this.drawBuffers, this.blendState, translucentPass);
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
        IrisRenderingPipeline pipeline = Iris.getRenderingPipeline();
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
        this.lastChunkProjection.set(matrix);
        if (this.uProjectionMatrix != null) {
            this.uProjectionMatrix.set(matrix);
        }
    }

    @Override
    public void setModelViewMatrix(Matrix4fc matrix) {
        this.lastChunkModelView.set(matrix);
        if (this.uModelViewMatrix != null) {
            this.uModelViewMatrix.set(matrix);
        }
    }

    /**
     * One-shot diagnostic: compares the chunk terrain matrices (which drive the water program's
     * {@code gl_ProjectionMatrix}/{@code gl_ModelViewMatrix}, i.e. {@code vReflData[1]} and the vertex view position)
     * against the captured {@code gbufferProjection}/{@code gbufferModelView} uniforms (which drive the SSR march's
     * {@code clipTarget} and the world-space round-trip). A non-zero delta on projection is the SSR screen-mapping
     * bug. Gated by {@code -Dimpetus.iris.waterMatrixProbe=N} (default 3 logs). Remove once diagnosed.
     */
    private void logWaterMatrixProbe() {
        if (waterProbeRemaining <= 0) {
            return;
        }
        waterProbeRemaining--;
        Matrix4f gbufProj = CapturedRenderingState.INSTANCE.getGbufferProjection();
        Matrix4f gbufMv = CapturedRenderingState.INSTANCE.getGbufferModelView();
        float projDelta = maxElementDelta(this.lastChunkProjection, gbufProj);
        float mvDelta = maxElementDelta(this.lastChunkModelView, gbufMv);
        PROBE_LOGGER.info("[WaterProbe] projDelta(chunk_u_Projection vs gbufferProjection)={}  "
                + "mvDelta(chunk_u_ModelView vs gbufferModelView)={}", projDelta, mvDelta);
        PROBE_LOGGER.info("[WaterProbe]   chunk u_ProjectionMatrix = {}", this.lastChunkProjection);
        PROBE_LOGGER.info("[WaterProbe]   gbufferProjection        = {}", gbufProj);
        PROBE_LOGGER.info("[WaterProbe]   chunk u_ModelViewMatrix  = {}", this.lastChunkModelView);
        PROBE_LOGGER.info("[WaterProbe]   gbufferModelView         = {}", gbufMv);
    }

    private static float maxElementDelta(Matrix4fc a, Matrix4fc b) {
        float max = 0.0f;
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                max = Math.max(max, Math.abs(a.get(c, r) - b.get(c, r)));
            }
        }
        return max;
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
