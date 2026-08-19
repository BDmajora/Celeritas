package com.bdmajora.impetus.iris.material;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import com.bdmajora.impetus.iris.shaderpack.materialmap.NamespacedId;

import java.util.Map;

/**
 * Render-thread-published, mesher-thread-consumed settings derived from the active shader pack — the (much smaller)
 * Impetus counterpart of Iris's {@code WorldRenderingSettings}. The pipeline publishes on construction and clears
 * on destroy; chunk-build workers only read.
 */
public final class WorldRenderingSettings {
    /**
     * The pack's {@code block.properties} mapping as a flat table indexed by the 1.12.2 global state id
     * ({@code Block.getStateId}: blockId | meta << 12, 16 bits), holding the pack's ID for that state or {@code -1}
     * (Iris parity: blocks the pack does not map get {@code mc_Entity.x = -1}).
     * <p>
     * {@code null} when no pack is active or the pack ships no {@code block.properties} — the mesher then emits raw
     * 1.12.2 block IDs and metadata, which is what classic OptiFine-era packs expect.
     */
    private static volatile int[] blockStateIds;
    private static volatile Map<NamespacedId, Integer> itemIds;
    private static volatile Map<NamespacedId, Integer> entityIds;
    private static volatile int voxelRenderDistanceChunks;

    private WorldRenderingSettings() {
    }

    public static int[] getBlockStateIds() {
        return blockStateIds;
    }

    public static void setBlockStateIds(int[] table) {
        blockStateIds = table;
    }

    public static Map<NamespacedId, Integer> getItemIds() {
        return itemIds;
    }

    public static void setItemIds(Map<NamespacedId, Integer> table) {
        itemIds = table;
    }

    public static Map<NamespacedId, Integer> getEntityIds() {
        return entityIds;
    }

    public static void setEntityIds(Map<NamespacedId, Integer> table) {
        entityIds = table;
    }

    /**
     * {@code dynamicHandLight} — when false the pack does not want the held item to emit light, so the
     * {@code heldBlockLightValue}/{@code heldBlockLightColor} uniforms report "nothing held".
     */
    private static boolean dynamicHandLight = true;
    /** {@code separateAo} — vanilla ambient occlusion is fed as its own vertex channel rather than baked into light. */
    private static boolean separateAo;
    /**
     * {@code oldLighting} — keep vanilla's fixed-function directional face shading (top 1.0, sides 0.8/0.6,
     * bottom 0.5). Packs that compute their own lighting from the face normal set it to <em>false</em> so that
     * shading is not applied twice; Body Camera Shader v1.6.1 does exactly that on line 1 of its
     * {@code shaders.properties}. Read on chunk-build worker threads, hence {@code volatile}.
     */
    private static volatile boolean oldLighting = true;
    /**
     * {@code oldHandLight} (default true) — when the offhand item emits more light than the mainhand, the
     * {@code heldItemId}/{@code heldBlockLightValue} uniforms report the offhand instead. OptiFine
     * {@code Shaders.java} does exactly this swap before uploading them.
     */
    private static boolean oldHandLight = true;
    /** {@code voxelizeLightBlocks} — emit geometry for light-emitting blocks so the shadow pass can voxelize them. */
    private static boolean voxelizeLightBlocks;

    public static boolean isOldHandLight() {
        return oldHandLight;
    }

    public static void setOldHandLight(boolean value) {
        oldHandLight = value;
    }

    /**
     * {@code layer.<rendertype>} overrides from block.properties: block → the chunk render layer the pack wants it
     * meshed into, replacing the block's own {@code canRenderInLayer} answer. Null when the pack declares none.
     */
    private static Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> blockRenderLayers;

    public static void setBlockRenderLayers(
            Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> table) {
        blockRenderLayers = table == null || table.isEmpty() ? null : table;
    }

    /**
     * @return the layer the pack forces for this block, or null to keep vanilla's choice. Kept as a fast null check
     * because it is consulted for every block in every chunk rebuild.
     */
    public static net.minecraft.util.BlockRenderLayer getForcedRenderLayer(net.minecraft.block.Block block) {
        Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> table = blockRenderLayers;
        return table == null ? null : table.get(block);
    }

    public static boolean isVoxelizeLightBlocks() {
        return voxelizeLightBlocks;
    }

    public static void setVoxelizeLightBlocks(boolean value) {
        voxelizeLightBlocks = value;
    }

    public static boolean isDynamicHandLight() {
        return dynamicHandLight;
    }

    public static void setDynamicHandLight(boolean value) {
        dynamicHandLight = value;
    }

    public static void setSeparateAo(boolean value) {
        separateAo = value;
        // The mesher bakes this into every chunk's vertex colour. Selecting a pack already calls
        // RenderGlobal.loadRenderers() (ShaderPackSelectScreen/ShaderPackConfigScreen), so the rebuild that
        // re-encodes the terrain with the new writer is already scheduled by the time this runs.
        com.bdmajora.impetus.engine.impl.render.chunk.ChunkColorWriter.SeparateAoState.set(value);
    }

    public static boolean isOldLighting() {
        return oldLighting;
    }

    /**
     * {@return whether vanilla's per-face directional shading must be suppressed}
     * <p>
     * Iris expresses this as {@code shouldDisableDirectionalShading() { return !oldLighting; }}
     * ({@code IrisRenderingPipeline.java:1167}) and applies it by forcing the shade lookup to {@code Direction.UP}
     * ({@code MixinClientLevel}). OptiFine 1.12.2 does the same thing by setting its three shade constants to 1.0
     * ({@code Shaders.updateBlockLightLevel}, {@code Shaders.java:1624}). Both therefore make every face full
     * brightness; {@link com.bdmajora.impetus.impl.render.terrain.compile.light.VintageDiffuseProvider} is the
     * equivalent site here.
     * <p>
     * <strong>Default divergence, deliberate.</strong> Iris defaults {@code oldLighting} to <em>false</em>
     * ({@code PackDirectives.java:92}) — shading off unless a pack asks for it. OptiFine defaults it to
     * <em>true</em> ({@code Shaders.java:1665}). Impetus keeps OptiFine's default because every pack here is a
     * 1.12.2 pack authored against OptiFine, and flipping it would silently re-light every pack that does not
     * declare the key. Packs that DO declare it are honoured exactly.
     */
    public static boolean shouldDisableDirectionalShading() {
        return !oldLighting;
    }

    public static void setOldLighting(boolean value) {
        // Baked into chunk vertex colour, so a change needs the chunk rebuild that selecting a pack already
        // schedules (RenderGlobal.loadRenderers()) — same situation as setSeparateAo above.
        oldLighting = value;
    }

    /**
     * {@code const float ambientOcclusionLevel} — how much of vanilla's baked per-block ambient occlusion to keep.
     * 1.0 is vanilla; 0.0 removes it entirely so a pack can supply its own AO without double-darkening corners.
     * <p>
     * Read on chunk-build worker threads ({@code LightDataCache}), hence {@code volatile}. Iris applies it by
     * rewriting the block's shade brightness ({@code MixinBlockStateBehavior}); {@link #applyAmbientOcclusionLevel}
     * is the same formula at the 1.12.2 equivalent site.
     */
    private static volatile float ambientOcclusionLevel = 1.0f;

    public static float getAmbientOcclusionLevel() {
        return ambientOcclusionLevel;
    }

    public static void setAmbientOcclusionLevel(float value) {
        // Terrain meshes bake AO into vertex colour, so a change needs the same chunk rebuild that selecting a pack
        // already schedules (RenderGlobal.loadRenderers()) — no extra invalidation needed here.
        ambientOcclusionLevel = Math.max(0.0f, Math.min(1.0f, value));
    }

    /**
     * Scales one block's vanilla AO value by the pack's {@code ambientOcclusionLevel}. Iris's exact expression
     * ({@code 1.0 - level * (1.0 - original)}): at level 1 the value passes through, at level 0 it becomes 1.0
     * (fully unoccluded).
     */
    public static float applyAmbientOcclusionLevel(float vanillaAo) {
        float level = ambientOcclusionLevel;
        if (level == 1.0f) {
            return vanillaAo;
        }
        return 1.0f - level * (1.0f - vanillaAo);
    }

    public static int getVoxelRenderDistanceChunks() {
        return voxelRenderDistanceChunks;
    }

    public static void setVoxelRenderDistanceChunks(int chunks) {
        voxelRenderDistanceChunks = Math.max(0, chunks);
    }

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

    public static int getItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }

        Item item = stack.getItem();
        ResourceLocation key = item.getRegistryName();
        int rawId = Item.getIdFromItem(item);
        return mappedOrRaw(itemIds, key, rawId);
    }

    public static int getEntityId(Entity entity) {
        if (entity == null) {
            return -1;
        }

        ResourceLocation key = EntityList.getKey(entity);
        int rawId = EntityList.getID(entity.getClass());
        return mappedOrRaw(entityIds, key, rawId);
    }

    public static int getBlockEntityId(TileEntity tileEntity) {
        if (tileEntity == null) {
            return -1;
        }

        ResourceLocation key = TileEntity.getKey(tileEntity.getClass());
        return mappedOrRaw(entityIds, key, -1);
    }

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
