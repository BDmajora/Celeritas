package com.bdmajora.impetus.impl.render.terrain;

import com.google.common.collect.ImmutableListMultimap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.BlockRenderLayer;
import com.bdmajora.impetus.engine.impl.render.chunk.RenderPassConfiguration;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.material.Material;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexType;
import com.bdmajora.impetus.ImpetusVintage;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class VintageRenderPassConfigurationBuilder {

    // forces the block atlas's filter state for a terrain pass; mods sometimes manage to corrupt it,
    // so it is set rather than assumed
    // allowMipmaps is not cosmetic - it is why torches had a one-pixel orange halo
    // vanilla 1.12 renders the CUTOUT layer with mipmapping switched *off*:
    // EntityRenderer.renderWorldPass calls setBlurMipmap(false, false) before renderBlockLayer(CUTOUT)
    // and restoreLastBlurMipmap() after, so CUTOUT geometry always samples mip 0 no matter how far away
    // this port previously ran every pass mipmapped and leaned on the material's mipped bit instead,
    // which the terrain shader turns into a -4.0 LOD bias (_material_mip_bias in chunk_material.glsl)
    // a bias only shifts the level, it does not pin it to 0, so past roughly four mip levels of
    // distance CUTOUT geometry is still mipmapped
    // that is visible on torches because torch_on.png is opaque in only two of its sixteen columns:
    // at mip 1 a 2x2 block pairing a transparent texel with a flame texel takes MipmapHelper's
    // "ignore the transparent one" branch, which keeps the flame colour at alpha = 255 >> 2 = 63
    // 63/255 clears the 0.1 alpha test, so a texel of the sprite's warm average (~130,106,58) draws
    // one texel outside the torch's real silhouette - zooming lowers the LOD back under the threshold,
    // which is why a zoom mod made it disappear
    // the bias is still worth keeping for the consolidated CUTOUT_MIPPED geometry; this just stops it
    // from being the *only* mechanism
    private static final class AtlasMipmapState implements TerrainRenderPass.PipelineState {
        private final boolean allowMipmaps;

        AtlasMipmapState(boolean allowMipmaps) {
            this.allowMipmaps = allowMipmaps;
        }

        // From game settings
        private static boolean mipmapsEnabled() {
            return Minecraft.getMinecraft().gameSettings.mipmapLevels > 0;
        }

        // Binds the block atlas with the right filtering
        private static void apply(boolean mipped) {
            var textureManager = Minecraft.getMinecraft().getTextureManager();
            textureManager.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            if (textureManager.getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE) instanceof AbstractTexture atlas) {
                atlas.setBlurMipmapDirect(false, mipped);
            }
        }

        // Applies texture state for the pass
        @Override
        public void setup() {
            apply(this.allowMipmaps && mipmapsEnabled());
        }

        // Restores default texture state
        @Override
        public void clear() {
            // Mirrors vanilla's restoreLastBlurMipmap(): everything drawn after this pass (entities, particles, the
            // held item) expects the atlas back in its mipmapped state.
            if (!this.allowMipmaps) {
                apply(mipmapsEnabled());
            }
        }
    }

    private static final TerrainRenderPass.PipelineState MIPMAPPED_STATE = new AtlasMipmapState(true);
    private static final TerrainRenderPass.PipelineState UNMIPMAPPED_STATE = new AtlasMipmapState(false);

    // One pass per vanilla render layer, with cutout and translucent handled by their own flags
    private static TerrainRenderPass.TerrainRenderPassBuilder builderForRenderType(BlockRenderLayer chunkRenderType, ChunkVertexType vertexType) {
        var extraDefines = new HashMap<String, String>();

        if (ImpetusVintage.options().quality.chunkFadeInDuration > 0) {
            extraDefines.put("CHUNK_FADE_IN_DURATION_MS", String.valueOf(ImpetusVintage.options().quality.chunkFadeInDuration));
        }

        var pipelineState = chunkRenderType == BlockRenderLayer.CUTOUT ? UNMIPMAPPED_STATE : MIPMAPPED_STATE;

        return TerrainRenderPass.builder().extraDefines(extraDefines).pipelineState(pipelineState).vertexType(vertexType).primitiveType(QuadPrimitiveType.TRIANGULATED);
    }

    // The full pass set, in vanilla's layer order
    public static RenderPassConfiguration<BlockRenderLayer> build(ChunkVertexType vertexType) {
        // First, build the main passes
        TerrainRenderPass solidPass, cutoutMippedPass, translucentPass;

        solidPass = builderForRenderType(BlockRenderLayer.SOLID, vertexType)
                .name("solid")
                .fragmentDiscard(false)
                .useReverseOrder(false)
                .build();
        cutoutMippedPass = builderForRenderType(BlockRenderLayer.CUTOUT_MIPPED, vertexType)
                .name("cutout_mipped")
                .fragmentDiscard(true)
                .useReverseOrder(false)
                .build();
        translucentPass = builderForRenderType(BlockRenderLayer.TRANSLUCENT, vertexType)
                .name("translucent")
                .fragmentDiscard(false)
                .useReverseOrder(true)
                .useTranslucencySorting(ImpetusVintage.options().performance.useTranslucentFaceSorting)
                .build();

        ImmutableListMultimap.Builder<BlockRenderLayer, TerrainRenderPass> vanillaRenderStages = ImmutableListMultimap.builder();

        // Build the materials for the vanilla render passes
        Material solidMaterial, cutoutMaterial, cutoutMippedMaterial, translucentMaterial;
        solidMaterial = new Material(solidPass, AlphaCutoffParameter.ZERO, true);
        translucentMaterial = new Material(translucentPass, AlphaCutoffParameter.ZERO, true);
        cutoutMippedMaterial = new Material(cutoutMippedPass, AlphaCutoffParameter.ONE_TENTH, true);

        vanillaRenderStages.put(BlockRenderLayer.SOLID, solidPass);
        vanillaRenderStages.put(BlockRenderLayer.TRANSLUCENT, translucentPass);

        // CUTOUT always keeps its own pass. Mipmapping is per-pass GL texture state, and vanilla renders this layer
        // unmipmapped, so CUTOUT cannot be folded into a mipmapped pass without reintroducing the torch halo
        // described on AtlasMipmapState. Consolidation still earns its keep below by letting CUTOUT_MIPPED share the
        // SOLID stage.
        TerrainRenderPass cutoutPass = builderForRenderType(BlockRenderLayer.CUTOUT, vertexType)
                .name("cutout")
                .fragmentDiscard(true)
                .useReverseOrder(false)
                .build();

        cutoutMaterial = new Material(cutoutPass, AlphaCutoffParameter.ONE_TENTH, false);
        vanillaRenderStages.put(BlockRenderLayer.CUTOUT, cutoutPass);

        if (ImpetusVintage.options().performance.useRenderPassConsolidation) {
            // Both are mipmapped, so CUTOUT_MIPPED can ride along with the SOLID stage.
            vanillaRenderStages.put(BlockRenderLayer.SOLID, cutoutMippedPass);
        } else {
            vanillaRenderStages.put(BlockRenderLayer.CUTOUT_MIPPED, cutoutMippedPass);
        }

        // Now build the material map
        Map<BlockRenderLayer, Material> renderTypeToMaterialMap = new Reference2ReferenceOpenHashMap<>(4,
                Reference2ReferenceOpenHashMap.VERY_FAST_LOAD_FACTOR);

        renderTypeToMaterialMap.put(BlockRenderLayer.SOLID, solidMaterial);
        renderTypeToMaterialMap.put(BlockRenderLayer.CUTOUT, cutoutMaterial);
        renderTypeToMaterialMap.put(BlockRenderLayer.CUTOUT_MIPPED, cutoutMippedMaterial);
        renderTypeToMaterialMap.put(BlockRenderLayer.TRANSLUCENT, translucentMaterial);

        for (BlockRenderLayer layer : BlockRenderLayer.values()) {
            if (!renderTypeToMaterialMap.containsKey(layer)) {
                ImpetusVintage.logger().warn("Falling back to cutout-like behavior for custom block render layer '{}'", layer);
                TerrainRenderPass pass = builderForRenderType(layer, vertexType).name(layer.name().toLowerCase(Locale.ROOT)).fragmentDiscard(true).useReverseOrder(false).build();
                Material material = new Material(pass, AlphaCutoffParameter.ONE_TENTH, true);
                vanillaRenderStages.put(layer, pass);
                renderTypeToMaterialMap.put(layer, material);
            }
        }

        var vanillaRenderStageMap = vanillaRenderStages.build();

        return new RenderPassConfiguration<>(renderTypeToMaterialMap,
                vanillaRenderStageMap.asMap(),
                solidMaterial,
                cutoutMippedMaterial,
                translucentMaterial);
    }
}
