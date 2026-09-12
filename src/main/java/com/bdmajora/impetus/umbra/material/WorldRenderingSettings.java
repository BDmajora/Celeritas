package com.bdmajora.impetus.umbra.material;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import com.bdmajora.impetus.umbra.shaderpack.materialmap.NamespacedId;

import java.util.Map;

// Settings the pipeline publishes on construction and clears on destroy, read by chunk-build workers
// A much smaller counterpart of Iris's WorldRenderingSettings
public final class WorldRenderingSettings {
    // block.properties mapping as a flat table indexed by 1.12.2 global state id (blockId | meta << 12, 16 bits),
    // holding the pack's id for that state or -1 (Umbra parity: unmapped blocks get mc_Entity.x = -1).
    // Null when no pack is active or the pack ships no block.properties — mesher then falls back to raw block id/meta.
    private static volatile int[] blockStateIds;
    private static volatile Map<NamespacedId, Integer> itemIds;
    private static volatile Map<NamespacedId, Integer> entityIds;
    private static volatile int voxelRenderDistanceChunks;

    private WorldRenderingSettings() {
    }

    // State id to pack id table, indexed by the raw state id
    public static int[] getBlockStateIds() {
        return blockStateIds;
    }

    // Published by the pipeline
    public static void setBlockStateIds(int[] table) {
        blockStateIds = table;
    }

    // Item registry name to pack id
    public static Map<NamespacedId, Integer> getItemIds() {
        return itemIds;
    }

    // Published by the pipeline
    public static void setItemIds(Map<NamespacedId, Integer> table) {
        itemIds = table;
    }

    // Entity registry name to pack id
    public static Map<NamespacedId, Integer> getEntityIds() {
        return entityIds;
    }

    // Published by the pipeline
    public static void setEntityIds(Map<NamespacedId, Integer> table) {
        entityIds = table;
    }

    // dynamicHandLight: when false the pack doesn't want the held item to emit light, so heldBlockLightValue/Color report "nothing held".
    private static boolean dynamicHandLight = true;
    // separateAo: vanilla AO is fed as its own vertex channel instead of being baked into the light channel.
    private static boolean separateAo;
    // oldLighting: keep vanilla's fixed-function directional face shading (top 1.0, sides 0.8/0.6, bottom 0.5).
    // Packs computing their own lighting from the face normal set this false to avoid double-shading (e.g. Body
    // Camera Shader v1.6.1 line 1 of shaders.properties). Read on chunk-build worker threads, hence volatile.
    private static volatile boolean oldLighting = true;
    // oldHandLight (default true): when the offhand emits more light than the mainhand, heldItemId/heldBlockLightValue
    // report the offhand instead — mirrors OptiFine's Shaders.java swap before uploading these uniforms.
    private static boolean oldHandLight = true;
    // voxelizeLightBlocks: emit geometry for light-emitting blocks so the shadow pass can voxelize them.
    private static boolean voxelizeLightBlocks;

    // Pack's oldHandLight directive
    public static boolean isOldHandLight() {
        return oldHandLight;
    }

    // Published by the pipeline
    public static void setOldHandLight(boolean value) {
        oldHandLight = value;
    }

    // layer.<rendertype> overrides from block.properties: block -> forced chunk render layer, overriding the
    // block's own canRenderInLayer answer. Null when the pack declares none.
    private static Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> blockRenderLayers;

    public static void setBlockRenderLayers(
            Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> table) {
        blockRenderLayers = table == null || table.isEmpty() ? null : table;
    }

    // Returns the layer the pack forces for this block, or null to keep vanilla's choice.
    // Kept as a fast null check since it's consulted for every block in every chunk rebuild.
    public static net.minecraft.util.BlockRenderLayer getForcedRenderLayer(net.minecraft.block.Block block) {
        Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> table = blockRenderLayers;
        return table == null ? null : table.get(block);
    }

    // Whether emissive blocks are voxelised for the pack
    public static boolean isVoxelizeLightBlocks() {
        return voxelizeLightBlocks;
    }

    // Published by the pipeline
    public static void setVoxelizeLightBlocks(boolean value) {
        voxelizeLightBlocks = value;
    }

    // Pack's dynamicHandLight directive
    public static boolean isDynamicHandLight() {
        return dynamicHandLight;
    }

    // Published by the pipeline
    public static void setDynamicHandLight(boolean value) {
        dynamicHandLight = value;
    }

    // Published by the pipeline
    public static void setSeparateAo(boolean value) {
        separateAo = value;
        // The mesher bakes this into every chunk's vertex colour. Selecting a pack already calls
        // RenderGlobal.loadRenderers() (ShaderPackSelectScreen/ShaderPackConfigScreen), so the rebuild that
        // re-encodes the terrain with the new writer is already scheduled by the time this runs.
        com.bdmajora.impetus.engine.impl.render.chunk.ChunkColorWriter.SeparateAoState.set(value);
    }

    // Pack's oldLighting directive, which changes how the mesher writes light
    public static boolean isOldLighting() {
        return oldLighting;
    }

    // Whether vanilla's per-face directional shading must be suppressed. Umbra forces the shade lookup to
    // Direction.UP (MixinClientLevel); OptiFine sets its three shade constants to 1.0 (Shaders.java:1624) — same
    // effect, full brightness every face. VintageDiffuseProvider is the equivalent site here.
    // Deliberate default divergence: Umbra defaults oldLighting false (shading off unless the pack asks for it),
    // but Impetus keeps OptiFine's true default since every pack here targets OptiFine — flipping it would
    // silently re-light packs that don't declare the key.
    public static boolean shouldDisableDirectionalShading() {
        return !oldLighting;
    }

    // Published by the pipeline
    public static void setOldLighting(boolean value) {
        // Baked into chunk vertex colour, so a change needs the chunk rebuild that selecting a pack already
        // schedules (RenderGlobal.loadRenderers()) — same situation as setSeparateAo above.
        oldLighting = value;
    }

    // ambientOcclusionLevel: how much of vanilla's baked per-block AO to keep. 1.0 is vanilla, 0.0 removes it
    // entirely so a pack can supply its own AO without double-darkening corners. Read on chunk-build worker
    // threads (LightDataCache), hence volatile. applyAmbientOcclusionLevel below is the 1.12.2 equivalent of
    // Umbra's MixinBlockStateBehavior shade rewrite.
    private static volatile float ambientOcclusionLevel = 1.0f;

    // Pack's ambientOcclusionLevel, applied by the mesher
    public static float getAmbientOcclusionLevel() {
        return ambientOcclusionLevel;
    }

    // Published by the pipeline
    public static void setAmbientOcclusionLevel(float value) {
        // Terrain meshes bake AO into vertex colour, so a change needs the same chunk rebuild that selecting a pack
        // already schedules (RenderGlobal.loadRenderers()) — no extra invalidation needed here.
        ambientOcclusionLevel = Math.max(0.0f, Math.min(1.0f, value));
    }

    // Scales one block's vanilla AO by the pack's ambientOcclusionLevel (Umbra's 1.0 - level*(1.0-original)):
    // level 1 passes the value through unchanged, level 0 flattens it to 1.0 (fully unoccluded).
    public static float applyAmbientOcclusionLevel(float vanillaAo) {
        float level = ambientOcclusionLevel;
        if (level == 1.0f) {
            return vanillaAo;
        }
        return 1.0f - level * (1.0f - vanillaAo);
    }

    // How far the voxel data extends
    public static int getVoxelRenderDistanceChunks() {
        return voxelRenderDistanceChunks;
    }

    // Published by the pipeline
    public static void setVoxelRenderDistanceChunks(int chunks) {
        voxelRenderDistanceChunks = Math.max(0, chunks);
    }

    // Pack id for a state, or the raw block id when the pack does not map it
    public static int getBlockStateId(IBlockState state) {
        if (state == null) {
            return -1;
        }
        int rawStateId = Block.getStateId(state) & 0xFFFF;
        int[] table = blockStateIds;
        if (table == null) {
            return Block.getIdFromBlock(state.getBlock());
        }
        return rawStateId < table.length ? table[rawStateId] : -1;
    }

    // Pack id for an item, or the raw item id
    public static int getItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }

        Item item = stack.getItem();
        ResourceLocation key = item.getRegistryName();
        int rawId = Item.getIdFromItem(item);
        return mappedOrRaw(itemIds, key, rawId);
    }

    // Pack id for an entity, or the raw entity id
    public static int getEntityId(Entity entity) {
        if (entity == null) {
            return -1;
        }

        ResourceLocation key = EntityList.getKey(entity);
        int rawId = EntityList.getID(entity.getClass());
        return mappedOrRaw(entityIds, key, rawId);
    }

    // Pack id for a tile entity via its registry key
    public static int getBlockEntityId(TileEntity tileEntity) {
        if (tileEntity == null) {
            return -1;
        }

        ResourceLocation key = TileEntity.getKey(tileEntity.getClass());
        return mappedOrRaw(entityIds, key, -1);
    }

    // Shared lookup: mapped id if present, else the raw one
    private static int mappedOrRaw(Map<NamespacedId, Integer> map, ResourceLocation key, int rawId) {
        if (map == null || map.isEmpty()) {
            return rawId;
        }
        if (key == null) {
            return -1;
        }
        Integer mapped = map.get(new NamespacedId(key.getNamespace(), key.getPath()));
        return mapped != null ? mapped : -1;
    }
}
