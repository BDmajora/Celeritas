package org.taumc.celeritas.impl.render.terrain.compile;

//? if 1.10.2 {
//?}
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import org.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import org.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import org.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.embeddedt.embeddium.impl.util.QuadUtil;
import org.lwjgl.opengl.GL11;
import org.taumc.celeritas.CeleritasVintage;
import org.taumc.celeritas.impl.extensions.TextureMapExtension;
import org.taumc.celeritas.impl.render.terrain.compile.light.LightDataCache;
import org.taumc.celeritas.impl.render.terrain.compile.pipeline.VintageBlockRenderer;
import org.taumc.celeritas.impl.world.WorldSlice;

import java.nio.ByteBuffer;
import java.util.Objects;

import static org.taumc.celeritas.lwjgl.LWJGLServiceProvider.LWJGL;

public class VintageChunkBuildContext extends ChunkBuildContext {
    public static final BlockRenderLayer[] LAYERS = BlockRenderLayer.values();
    private final TextureMapExtension textureAtlas;
    private final net.minecraft.client.renderer.BufferBuilder[] worldRenderers = new net.minecraft.client.renderer.BufferBuilder[LAYERS.length];
    private final boolean[] usedWorldRenderers = new boolean[LAYERS.length];
    /**
     * Per layer, the block attribution of the vanilla-sourced quads (fluids and other non-model renders) as runs of
     * int triples {@code (quadEndExclusive, mcEntityId, mcEntityAux)}, recorded while a shader pack is active so
     * {@code mc_Entity} survives the vanilla BufferBuilder round-trip. See {@link #recordVanillaBlockAttribution}.
     */
    private final it.unimi.dsi.fastutil.ints.IntArrayList[] vanillaBlockRuns =
            new it.unimi.dsi.fastutil.ints.IntArrayList[LAYERS.length];
    @Getter
    private int offX, offY, offZ;
    @Getter
    private final WorldSlice worldSlice;
    @Getter
    private final VintageBlockRenderer blockRenderer;
    private final RenderPassConfiguration<?> renderPassConfiguration;
    private final LightDataCache lightDataCache;
    private final boolean useRenderPassOptimization;

    public VintageChunkBuildContext(WorldClient world, RenderPassConfiguration renderPassConfiguration) {
        super(renderPassConfiguration);
        this.renderPassConfiguration = renderPassConfiguration;
        this.worldSlice = new WorldSlice(world);
        this.lightDataCache = new LightDataCache(this.worldSlice);
        this.blockRenderer = new VintageBlockRenderer(this, lightDataCache);
        this.textureAtlas = (TextureMapExtension) Minecraft.getMinecraft().getTextureMapBlocks();
        this.useRenderPassOptimization = CeleritasVintage.options().performance.useRenderPassOptimization;
    }

    public void setupTranslation(int x, int y, int z) {
        this.lightDataCache.reset(x, y, z);

        this.offX = x;
        this.offY = y;
        this.offZ = z;
    }

    public net.minecraft.client.renderer.BufferBuilder getBufferForLayer(BlockRenderLayer layer) {
        var builder = this.worldRenderers[layer.ordinal()];
        if (builder == null) {
            builder = new net.minecraft.client.renderer.BufferBuilder(131072);
            this.worldRenderers[layer.ordinal()] = builder;
        }
        if (!this.usedWorldRenderers[layer.ordinal()]) {
            builder.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            builder.setTranslation(-this.offX, -this.offY, -this.offZ);
            this.usedWorldRenderers[layer.ordinal()] = true;
            if (this.vanillaBlockRuns[layer.ordinal()] != null) {
                this.vanillaBlockRuns[layer.ordinal()].clear();
            }
        }
        return builder;
    }

    /**
     * Records which block the vanilla-buffered quads emitted since the last call belong to, so
     * {@link #copyBlockData} can fill {@code mc_Entity} for the vanilla-sourced path (fluids and other non-model
     * renders). Call right after every {@code dispatcher.renderBlock} into {@link #getBufferForLayer}'s builder.
     * No-op when no shader pack is active.
     */
    public void recordVanillaBlockAttribution(BlockRenderLayer layer, net.minecraft.block.state.IBlockState state) {
        if (!org.taumc.celeritas.iris.terrain.IrisTerrainProgramOverride.areShadersActive()) {
            return;
        }
        int i = layer.ordinal();
        var builder = this.worldRenderers[i];
        if (builder == null || !this.usedWorldRenderers[i]) {
            return;
        }
        var runs = this.vanillaBlockRuns[i];
        if (runs == null) {
            runs = new it.unimi.dsi.fastutil.ints.IntArrayList();
            this.vanillaBlockRuns[i] = runs;
        }
        int quadCount = builder.getVertexCount() / 4;
        int lastEnd = runs.isEmpty() ? 0 : runs.getInt(runs.size() - 3);
        if (quadCount <= lastEnd) {
            return; // the block emitted nothing into this layer
        }
        int id;
        int aux;
        int[] idTable = org.taumc.celeritas.iris.material.WorldRenderingSettings.getBlockStateIds();
        if (idTable != null) {
            // Pack ships block.properties: mc_Entity = (pack id or -1, fluid flag) — Iris semantics.
            id = idTable[net.minecraft.block.Block.getStateId(state) & 0xFFFF];
            aux = state.getMaterial().isLiquid() ? 1 : 0;
        } else {
            // No block.properties: raw 1.12.2 id + metadata, the classic OptiFine-pack contract.
            id = net.minecraft.block.Block.getIdFromBlock(state.getBlock());
            aux = state.getBlock().getMetaFromState(state);
        }
        runs.add(quadCount);
        runs.add(id);
        runs.add(aux);
    }

    public void convertVanillaDataToCeleritasData(ChunkBuildBuffers buffers) {
        var renderers = this.worldRenderers;
        var used = this.usedWorldRenderers;
        for (int i = 0; i < renderers.length; i++) {
            if(!used[i]) {
                continue;
            }
            var bufferBuilder = Objects.requireNonNull(renderers[i]);
            bufferBuilder.finishDrawing();
            used[i] = false;
            ByteBuffer rawBuffer = bufferBuilder.getByteBuffer();
            var material = buffers.getRenderPassConfiguration().getMaterialForRenderType(LAYERS[i]);
            copyBlockData(rawBuffer, buffers, material, this.vanillaBlockRuns[i]);
        }
    }

    @Override
    public void cleanup() {
        super.cleanup();
        this.worldSlice.reset();
        for (int i = 0; i < LAYERS.length; i++) {
            if (this.usedWorldRenderers[i]) {
                this.worldRenderers[i].finishDrawing();
                this.usedWorldRenderers[i] = false;
            }
        }
    }

    private Material selectMaterial(Material material, TextureAtlasSprite sprite) {
        if (sprite != null && sprite.getClass() == TextureAtlasSprite.class && !sprite.hasAnimationMetadata() && this.useRenderPassOptimization) {
            var transparencyLevel = ((SpriteTransparencyLevel.Holder)sprite).embeddium$getTransparencyLevel();
            if (transparencyLevel == SpriteTransparencyLevel.OPAQUE) {
                // Downgrade to solid
                return this.renderPassConfiguration.defaultSolidMaterial();
            } else if (material == this.renderPassConfiguration.defaultTranslucentMaterial() && transparencyLevel != SpriteTransparencyLevel.TRANSLUCENT) {
                // Downgrade to cutout
                return this.renderPassConfiguration.defaultCutoutMippedMaterial();
            }
        }
        return material;
    }

    private static final int BLOCK_VERTEX_FORMAT_SIZE;

    static {
        var format = DefaultVertexFormats.BLOCK;
        int size = 0;
        for (int i = 0; i < format.getElementCount(); i++) {
            size += format.getElement(i).getSize();
        }
        BLOCK_VERTEX_FORMAT_SIZE = size;
    }

    private void copyBlockData(ByteBuffer source, ChunkBuildBuffers buffers, Material material,
                               it.unimi.dsi.fastutil.ints.IntArrayList blockRuns) {
        int vsize = BLOCK_VERTEX_FORMAT_SIZE;
        int numQuads = source.limit() / (vsize * 4);
        long ptr = LWJGL.memAddress(source);
        var quad = ChunkVertexEncoder.Vertex.uninitializedQuad();
        var animatedSpritesList = ((MinecraftBuiltRenderSectionData<TextureAtlasSprite, TileEntity>)buffers.getSectionContextBundle()).animatedSprites;
        // Walk the per-block attribution runs in lockstep with the quads (see recordVanillaBlockAttribution).
        int runCursor = 0;
        int runId = 0;
        int runAux = 0;
        for(int q = 0; q < numQuads; q++) {
            if (blockRuns != null) {
                while (runCursor < blockRuns.size() && q >= blockRuns.getInt(runCursor)) {
                    runCursor += 3;
                }
                if (runCursor < blockRuns.size()) {
                    runId = blockRuns.getInt(runCursor + 1);
                    runAux = blockRuns.getInt(runCursor + 2);
                }
            }
            float uSum = 0, vSum = 0;
            for(int v = 0; v < 4; v++) {
                var vertex = quad[v];
                vertex.x = LWJGL.memGetFloat(ptr);
                vertex.y = LWJGL.memGetFloat(ptr + 4);
                vertex.z = LWJGL.memGetFloat(ptr + 8);
                vertex.color = LWJGL.memGetInt(ptr + 12);
                vertex.u = LWJGL.memGetFloat(ptr + 16);
                vertex.v = LWJGL.memGetFloat(ptr + 20);
                uSum += vertex.u;
                vSum += vertex.v;
                vertex.light = LWJGL.memGetInt(ptr + 24);
                ptr += vsize;
            }
            TextureAtlasSprite sprite = this.textureAtlas.celeritas$findFromUV(uSum * 0.25f, vSum * 0.25f);
            if (sprite != null && sprite.hasAnimationMetadata()) {
                animatedSpritesList.add(sprite);
            }
            int trueNormal = QuadUtil.calculateNormal(quad);
            for (int v = 0; v < 4; v++) {
                var vertex = quad[v];
                vertex.vanillaNormal = trueNormal;
                vertex.trueNormal = trueNormal;
            }
            if (org.taumc.celeritas.iris.terrain.IrisTerrainProgramOverride.areShadersActive()) {
                // OptiFine extended attributes for the vanilla-sourced path (fluids etc.). mc_Entity comes from the
                // per-block attribution runs recorded during meshing; mid-tex and tangent are derivable here.
                float midU = 0.0f, midV = 0.0f;
                if (sprite != null) {
                    midU = (sprite.getMinU() + sprite.getMaxU()) * 0.5f;
                    midV = (sprite.getMinV() + sprite.getMaxV()) * 0.5f;
                }
                int tangent = org.taumc.celeritas.iris.vertices.NormalHelper.computeTangent(
                        org.taumc.celeritas.iris.vertices.NormI8.unpackX(trueNormal),
                        org.taumc.celeritas.iris.vertices.NormI8.unpackY(trueNormal),
                        org.taumc.celeritas.iris.vertices.NormI8.unpackZ(trueNormal),
                        quad[0].x, quad[0].y, quad[0].z, quad[0].u, quad[0].v,
                        quad[1].x, quad[1].y, quad[1].z, quad[1].u, quad[1].v,
                        quad[2].x, quad[2].y, quad[2].z, quad[2].u, quad[2].v);
                for (int v = 0; v < 4; v++) {
                    var vertex = quad[v];
                    vertex.midTexU = midU;
                    vertex.midTexV = midV;
                    vertex.tangent = tangent;
                    vertex.blockId = runId;
                    vertex.blockData = runAux;
                }
            }
            ModelQuadFacing facing = QuadUtil.findNormalFace(trueNormal);
            Material correctMaterial = selectMaterial(material, sprite);
            buffers.get(correctMaterial).getVertexBuffer(facing).push(quad, correctMaterial);
        }
    }
}
